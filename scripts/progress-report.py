#!/usr/bin/env python3
"""The coach's progress report over a Documents/MinimalPairs folder.

Usage:
  progress-report.py <folder> [--weeks 6] [--out report.md] [--plan-out plan.json]
                     [--ledger logs/pronunciation-ledger.json] [--catalog catalog.json]
                     [--note TEXT] [--now 2026-09-11T12:00:00Z]
  progress-report.py --selftest

Reads plan.json, state.json and sessions/ (docs/CONTRACT.md) and writes a
Markdown report with

  1. consistency: per ISO week the days practised, sessions and minutes
     against plan.weekly_minutes_target, the current and longest streak, and a
     one-line verdict;
  2. one row per contrast: level, untrained-perception percent per week over
     the last four weeks, Say-it percent per week, the flag and the
     recommendation;
  3. the plan changes it recommends, in words.

Flags (thresholds from docs/CONTRACT.md, "Coach side"):
  regression  the last session that probed the contrast scored >= 15 points
              below the mean of the previous two probes (untrained percent)
  plateau     the last three weeks with probes all sit below 90 % within
              +-5 points of each other, at the same level, and are consecutive
              (at most one week without a probe between them); three probes
              spread wider than that are a consistency problem, not a plateau,
              and the row says so instead of raising the flag
  mastered    the last three probes >= 95 % untrained and the last three
              Say-it sessions >= 85 %
  untested    the contrast has perception data but no Say-it pair yet

Recommended plan changes (written by --plan-out on top of plan-from-ledger's
rules, starting from the levers *and the weights* of the current plan.json: a
weight is changed only where this report's evidence justifies it, or where the
plan does not name the contrast at all and plan-from-ledger's score rule fills
it in. With --ledger the coach's own counts are the fresh evidence and
plan-from-ledger's weights are kept for every contrast):
  regression  weight x 1.5, capped at 1.0 (plan-from-ledger's own rule); when
              the week of that probe had a single session the report calls it
              a consistency problem, not a skill problem
  plateau     weight x 1.25, capped at 1.0; feedback "brief" at level >= 3
  mastered    weight to the floor (0.15) and the level pinned at 4
  lag         production_pairs + 4 (max 30) when Say it lags perception by
              >= 20 points on any contrast over the reported weeks
  untested    production_pairs back to 8 when the plan has switched Say it off
  shortfall   band widened to high,mid,low when the recent sessions report
              untrained_shortfall on >= 10 % of their trials (docs/CONTRACT.md,
              "untrained_shortfall"); plan-from-ledger only warns when the
              current plan names a narrower band, so the widening happens here

Five summary lines go to stdout. --selftest builds synthetic histories that
trigger every flag and checks the flags and the plan deltas.

Standard library only, Python 3.9+. plan-from-ledger.py (same folder) is
imported by path for the session helpers and the plan rules.
"""

import argparse
import datetime as _dt
import importlib.util
import json
import os
import sys
import tempfile

DEFAULT_WEEKS = 6
SPARK_WEEKS = 4
PLATEAU_WEEKS = 3
PLATEAU_SPREAD = 5.0      # +- points around the mean
PLATEAU_BELOW = 90.0
PLATEAU_FACTOR = 1.25
# The three plateau weeks must be consecutive, with at most one week without a
# probe between them: four calendar weeks from the first to the last.
PLATEAU_MAX_SPAN = PLATEAU_WEEKS + 1
MASTERED_SESSIONS = 3
MASTERED_UNTRAINED = 95.0
MASTERED_PRODUCTION = 85.0
LAG_POINTS = 20.0
LAG_EXTRA_PAIRS = 4
GOOD_DAYS_PER_WEEK = 3
FLAG_ORDER = ("regression", "plateau", "mastered", "untested")


def here():
    return os.path.dirname(os.path.abspath(__file__))


def load_plan_from_ledger():
    path = os.path.join(here(), "plan-from-ledger.py")
    spec = importlib.util.spec_from_file_location("plan_from_ledger", path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


PFL = load_plan_from_ledger()


def warn(msg):
    print("progress-report: " + msg, file=sys.stderr)


def is_int(x):
    return isinstance(x, int) and not isinstance(x, bool)


def is_num(x):
    return isinstance(x, (int, float)) and not isinstance(x, bool)


def iso_week(d):
    y, w, _ = d.isocalendar()
    return "%04d-W%02d" % (y, w)


def week_monday(d):
    d = d.date() if isinstance(d, _dt.datetime) else d
    return d - _dt.timedelta(days=d.weekday())


def week_start(label):
    """The Monday of an ISO week label like 2026-W37."""
    year, week = label.split("-W")
    return _dt.date.fromisocalendar(int(year), int(week), 1)


def week_span(weeks):
    """Calendar weeks covered from the first to the last label, inclusive."""
    return (week_start(weeks[-1]) - week_start(weeks[0])).days // 7 + 1


def last_weeks(now, n):
    """The n ISO week labels ending with the week of now, oldest first."""
    monday = week_monday(now)
    return [iso_week(monday - _dt.timedelta(days=7 * (n - 1 - i))) for i in range(n)]


def pct_str(x):
    return "-" if x is None else "%.0f" % x


# ------------------------------------------------------------------ gather

def load_folder(folder):
    plan = PFL.load_json(os.path.join(folder, "plan.json"))
    state = PFL.load_json(os.path.join(folder, "state.json"))
    sessions = PFL.load_sessions(os.path.join(folder, "sessions"))
    return (plan if isinstance(plan, dict) else None,
            state if isinstance(state, dict) else None, sessions)


def practice_days(state, sessions):
    """{date: {"sessions": n, "seconds": s}} from state.practice.days when the
    app wrote it, else from the session files."""
    days = {}
    practice = (state or {}).get("practice")
    if isinstance(practice, dict) and isinstance(practice.get("days"), dict):
        for day, d in practice["days"].items():
            if not isinstance(d, dict):
                continue
            try:
                _dt.date.fromisoformat(day)
            except (TypeError, ValueError):
                continue
            n = d.get("sessions")
            sec = d.get("seconds")
            days[day] = {"sessions": n if is_int(n) else 1, "seconds": sec if is_int(sec) else 0}
        if days:
            return days
    for started, s in sessions:
        day = started.date().isoformat()
        d = days.setdefault(day, {"sessions": 0, "seconds": 0})
        d["sessions"] += 1
        summary = s.get("summary")
        dur = summary.get("duration_s") if isinstance(summary, dict) else None
        d["seconds"] += dur if is_int(dur) and dur > 0 else 0
    return days


def streaks(days, now):
    """(current streak ending today or yesterday, longest streak) in days."""
    dates = sorted(_dt.date.fromisoformat(d) for d in days)
    longest = run = 0
    prev = None
    for d in dates:
        run = run + 1 if prev is not None and (d - prev).days == 1 else 1
        longest = max(longest, run)
        prev = d
    today = now.date()
    current = 0
    if dates and (today - dates[-1]).days <= 1:
        current = run
    return current, longest


def weekly_consistency(days, weeks):
    out = {w: {"days": 0, "sessions": 0, "minutes": 0.0} for w in weeks}
    for day, d in days.items():
        w = iso_week(_dt.date.fromisoformat(day))
        if w in out:
            out[w]["days"] += 1
            out[w]["sessions"] += d["sessions"]
            out[w]["minutes"] += d["seconds"] / 60.0
    return out


def contrast_history(sessions):
    """Per contrast: probes [(started, pct, level)], prods [(started, pct, pairs)],
    weekly {week: {"ut", "uc", "pp", "pk"}}, sessions_in_week {week: n}."""
    hist = {}
    sessions_in_week = {}
    for started, s in sessions:
        week = iso_week(started)
        sessions_in_week[week] = sessions_in_week.get(week, 0) + 1
        for c, g in PFL.session_contrast_stats(s).items():
            h = hist.setdefault(c, {"probes": [], "prods": [], "weekly": {}, "level": None})
            wk = h["weekly"].setdefault(week, {"ut": 0, "uc": 0, "pp": 0, "pk": 0, "levels": set()})
            if g["untrained_trials"]:
                h["probes"].append((started, 100.0 * g["untrained_correct"] / g["untrained_trials"], g["level"]))
                wk["ut"] += g["untrained_trials"]
                wk["uc"] += g["untrained_correct"]
            if g["prod_pairs"]:
                h["prods"].append((started, 100.0 * g["prod_points"] / (2.0 * g["prod_pairs"]), g["prod_pairs"]))
                wk["pp"] += g["prod_pairs"]
                wk["pk"] += g["prod_points"]
            if g["level"] is not None:
                wk["levels"].add(g["level"])
                h["level"] = g["level"]
    return hist, sessions_in_week


def weekly_pct(h, week, kind):
    wk = h["weekly"].get(week)
    if not wk:
        return None
    if kind == "untrained":
        return 100.0 * wk["uc"] / wk["ut"] if wk["ut"] else None
    return 100.0 * wk["pk"] / (2.0 * wk["pp"]) if wk["pp"] else None


# ------------------------------------------------------------------- flags

def flag_contrast(h, sessions_in_week):
    """(set of flags, details dict) for one contrast's history."""
    flags = set()
    detail = {}
    probe_pcts = [p[1] for p in h["probes"]]
    drop = PFL.regression_drop(probe_pcts)
    if drop is not None and drop >= PFL.REGRESSION_DROP:
        flags.add("regression")
        last_started = h["probes"][-1][0]
        detail["drop"] = drop
        detail["regression_week"] = iso_week(last_started)
        detail["lonely"] = sessions_in_week.get(iso_week(last_started), 0) <= 1
    # plateau: the last three weeks with probes
    weeks = sorted(w for w in h["weekly"] if weekly_pct(h, w, "untrained") is not None)[-PLATEAU_WEEKS:]
    if len(weeks) == PLATEAU_WEEKS:
        pcts = [weekly_pct(h, w, "untrained") for w in weeks]
        levels = set()
        for w in weeks:
            levels |= h["weekly"][w]["levels"]
        mean = sum(pcts) / len(pcts)
        if (all(p < PLATEAU_BELOW for p in pcts) and all(abs(p - mean) <= PLATEAU_SPREAD for p in pcts)
                and len(levels) <= 1):
            detail["plateau_mean"] = mean
            detail["plateau_level"] = next(iter(levels)) if levels else None
            span = week_span(weeks)
            if span <= PLATEAU_MAX_SPAN:
                flags.add("plateau")
                detail["plateau_weeks"] = weeks
            else:
                # three flat probes spread over more weeks than that: the learner
                # practised the contrast rarely, which is a consistency problem
                # (docs/ADAPTATION.md, "Consistency"), not a plateau
                detail["plateau_scattered"] = weeks
                detail["plateau_span"] = span
    # mastered: last three probes and last three Say-it sessions
    prod_pcts = [p[1] for p in h["prods"]]
    if (len(probe_pcts) >= MASTERED_SESSIONS and len(prod_pcts) >= MASTERED_SESSIONS
            and all(p >= MASTERED_UNTRAINED for p in probe_pcts[-MASTERED_SESSIONS:])
            and all(p >= MASTERED_PRODUCTION for p in prod_pcts[-MASTERED_SESSIONS:])):
        flags.add("mastered")
    if not h["prods"]:
        flags.add("untested")
    return flags, detail


def production_lag(h, weeks):
    """Points by which Say it lags untrained perception over the given weeks,
    or None without both signals."""
    ut = uc = pp = pk = 0
    for w in weeks:
        wk = h["weekly"].get(w)
        if not wk:
            continue
        ut += wk["ut"]
        uc += wk["uc"]
        pp += wk["pp"]
        pk += wk["pk"]
    if not ut or not pp:
        return None
    return 100.0 * uc / ut - 100.0 * pk / (2.0 * pp)


# ------------------------------------------------------------------ analyse

def analyse(folder, weeks_n, now, catalog=None, ledger=None, note=None, plan_out=None):
    """Everything the report and the plan need, as one dict."""
    plan, state, sessions = load_folder(folder)
    weeks = last_weeks(now, weeks_n)
    spark_weeks = weeks[-SPARK_WEEKS:]
    days = practice_days(state, sessions)
    current, longest = streaks(days, now)
    if state is not None:
        if is_int(state.get("streak_days")):
            current = state["streak_days"]
        practice = state.get("practice")
        if isinstance(practice, dict) and is_int(practice.get("longest_streak")):
            longest = max(longest, practice["longest_streak"])
    target = (plan or {}).get("weekly_minutes_target")
    target = target if is_int(target) else PFL.DEFAULT_WEEKLY_MINUTES
    weekly = weekly_consistency(days, weeks)
    # the current week is partial: judge the completed weeks from the first one
    # with any practice on; before the learner started there is nothing to judge,
    # and with no completed week the current one has to do
    active = [w for w in weeks if weekly[w]["sessions"] > 0]
    judged = [w for w in weeks[:-1] if active and w >= active[0]]
    if not judged:
        judged = weeks[-1:]
    mean_days = sum(weekly[w]["days"] for w in judged) / float(len(judged))
    mean_minutes = sum(weekly[w]["minutes"] for w in judged) / float(len(judged))
    if not active:
        verdict = "no practice in the last %d weeks" % len(weeks)
    elif mean_minutes >= target and mean_days >= GOOD_DAYS_PER_WEEK:
        verdict = "on target: %.0f min over %.1f days a week (target %d min)" % (mean_minutes, mean_days, target)
    elif mean_minutes >= target:
        verdict = ("minutes on target (%.0f of %d) but bunched into %.1f days a week; spread them out"
                   % (mean_minutes, target, mean_days))
    else:
        verdict = "below target: %.0f of %d min a week over %.1f days" % (mean_minutes, target, mean_days)
    if active and judged == weeks[-1:]:
        verdict += " (only this week has practice so far)"
    consistency = {
        "weeks": weeks, "weekly": weekly, "target": target, "streak": current, "longest": longest, "judged": judged,
        "mean_days": mean_days, "mean_minutes": mean_minutes, "verdict": verdict,
        "sessions": len(sessions),
    }

    hist, sessions_in_week = contrast_history(sessions)
    contrasts_cat, _voices = PFL.load_contrasts(catalog or PFL.default_catalog_path())
    order = [c[0] for c in contrasts_cat] + sorted(c for c in hist if c not in {x[0] for x in contrasts_cat})
    state_contrasts = (state or {}).get("contrasts") if state else None
    rows = []
    lag_any = []
    for c in order:
        h = hist.get(c)
        if h is None:
            continue
        level = None
        if isinstance(state_contrasts, dict) and isinstance(state_contrasts.get(c), dict):
            lv = state_contrasts[c].get("level")
            level = lv if is_int(lv) else None
        if level is None:
            level = h["level"]
        flags, detail = flag_contrast(h, sessions_in_week)
        lag = production_lag(h, weeks)
        if lag is not None and lag >= LAG_POINTS:
            detail["lag"] = lag
            lag_any.append(c)
        rows.append({
            "contrast": c, "level": level, "flags": flags, "detail": detail,
            "untrained": [weekly_pct(h, w, "untrained") for w in spark_weeks],
            "production": [weekly_pct(h, w, "production") for w in spark_weeks],
            "last_untrained": h["probes"][-1][1] if h["probes"] else None,
            "last_production": h["prods"][-1][1] if h["prods"] else None,
        })

    new_plan, base_info = build_plan(folder, plan, rows, lag_any, now, catalog, ledger, note, plan_out)
    changes = plan_changes(plan, new_plan, rows, lag_any, base_info)
    for r in rows:
        r["recommendation"] = recommendation(r, new_plan, base_info)
    return {
        "folder": folder, "now": now, "plan": plan, "state": state, "sessions": sessions,
        "weeks": weeks, "spark_weeks": spark_weeks, "consistency": consistency,
        "rows": rows, "lag": lag_any, "new_plan": new_plan, "base_info": base_info, "changes": changes,
    }


# --------------------------------------------------------------------- plan

def plan_argv(folder, plan, catalog, ledger, note, plan_out):
    """plan-from-ledger arguments that carry the current plan's levers over."""
    plan = plan or {}
    argv = ["--out", plan_out or os.path.join(folder, "plan.json"),
            "--sessions", os.path.join(folder, "sessions"),
            "--catalog", catalog or PFL.default_catalog_path()]
    if ledger:
        argv += ["--ledger", ledger]
    if note is not None:
        argv += ["--note", note]
    elif isinstance(plan.get("note"), str):
        argv += ["--note", plan["note"]]
    if is_int(plan.get("trials_per_session")):
        argv += ["--trials", str(plan["trials_per_session"])]
    if is_num(plan.get("untrained_ratio")):
        argv += ["--untrained-ratio", str(plan["untrained_ratio"])]
    band = plan.get("band")
    if isinstance(band, list) and band and all(b in PFL.BANDS for b in band):
        argv += ["--band", ",".join(band)]
    if plan.get("feedback") in PFL.FEEDBACK:
        argv += ["--feedback", plan["feedback"]]
    voices = plan.get("voices")
    if isinstance(voices, list) and voices and all(isinstance(v, str) for v in voices):
        argv += ["--voices", ",".join(voices)]
    for key, opt in (("production_pairs", "--production-pairs"),
                     ("production_threshold", "--production-threshold"),
                     ("max_level", "--max-level"),
                     ("weekly_minutes_target", "--weekly-minutes")):
        if is_int(plan.get(key)):
            argv += [opt, str(plan[key])]
    levels = plan.get("levels")
    if isinstance(levels, dict):
        for c, lv in levels.items():
            if is_int(lv) and 1 <= lv <= 4:
                argv += ["--pin", "%s=%d" % (c, lv)]
    return argv


def seed_weights(plan, new_plan, info, from_ledger):
    """Start the recommended weights from the current plan's, so the report only
    moves a weight where its own evidence justifies it (regression here, plateau
    and mastered in build_plan). plan-from-ledger's computed weight is kept for a
    contrast the plan does not name, for the production-only ones, and for every
    contrast when a --ledger was given (then the coach's counts are the fresh
    evidence). Without this, a report run straight from Drive with no ledger
    would collapse every undrilled contrast to the floor."""
    info["seeded"] = set()
    info["from_ledger"] = from_ledger
    old_w = (plan or {}).get("weights")
    if from_ledger or not isinstance(old_w, dict):
        return
    production_only = {cid for cid, _dw, po in info["contrasts"] if po}
    for cid in new_plan["weights"]:
        w = old_w.get(cid)
        if cid in production_only or not is_num(w) or not 0.0 <= w <= 1.0:
            continue
        w = float(w)
        if cid in info["regressed"]:
            w = min(1.0, w * PFL.REGRESSION_FACTOR)
        new_plan["weights"][cid] = PFL.round2(w)
        info["seeded"].add(cid)


def widen_band(new_plan, info):
    """docs/CONTRACT.md, "untrained_shortfall": when the honest probe ran dry on
    >= 10 % of the recent trials the ceiling is widened to all three bands.
    plan-from-ledger only warns when a --band was given, and the report always
    carries the current plan's band over, so the widening is applied here."""
    if info.get("shortfall_ratio", 0.0) < PFL.SHORTFALL_WARN or "low" in new_plan["band"]:
        return
    info["band_before"] = list(new_plan["band"])
    new_plan["band"] = list(PFL.WIDE_BAND)
    info["band_widened"] = True
    warn("untrained_shortfall on %d of %d recent trials (%.0f %%): widening the recommended band "
         "%s -> %s" % (info["shortfall"], info["trials"], 100 * info["shortfall_ratio"],
                       ",".join(info["band_before"]), ",".join(new_plan["band"])))


def build_plan(folder, plan, rows, lag_any, now, catalog, ledger, note, plan_out):
    """The recommended plan: the current plan's levers and weights, with
    plan-from-ledger's rules and the flag deltas where the evidence justifies."""
    args = PFL.parse_args(plan_argv(folder, plan, catalog, ledger, note, plan_out))
    new_plan, info = PFL.build_plan(args, now)
    info["base_weights"] = dict(new_plan["weights"])
    seed_weights(plan, new_plan, info, bool(ledger))
    weights = new_plan["weights"]
    for r in rows:
        c = r["contrast"]
        if c not in weights:
            continue
        if "plateau" in r["flags"]:
            weights[c] = PFL.round2(min(1.0, weights[c] * PLATEAU_FACTOR))
            if r["level"] is not None and r["level"] >= 3 and new_plan["feedback"] == "full":
                new_plan["feedback"] = "brief"
        if "mastered" in r["flags"]:
            weights[c] = PFL.round2(PFL.FLOOR)
            new_plan["levels"][c] = 4
    if lag_any:
        new_plan["production_pairs"] = min(30, new_plan["production_pairs"] + LAG_EXTRA_PAIRS)
    if any("untested" in r["flags"] for r in rows) and new_plan["production_pairs"] == 0:
        new_plan["production_pairs"] = PFL.DEFAULT_PRODUCTION_PAIRS
    widen_band(new_plan, info)
    return new_plan, info


def recommendation(r, new_plan, info):
    c = r["contrast"]
    d = r["detail"]
    parts = []
    w = new_plan["weights"].get(c)
    if "regression" in r["flags"]:
        s = "weight x1.5 to %.2f (down %.0f points)" % (w, d["drop"])
        if d.get("lonely"):
            s += "; one session in %s: a consistency problem, not a skill problem" % d["regression_week"]
        parts.append(s)
    if "plateau" in r["flags"]:
        s = "weight x1.25 to %.2f (%s at %.0f %%)" % (w, "-".join(d["plateau_weeks"]), d["plateau_mean"])
        if r["level"] is not None and r["level"] >= 3:
            s += "; feedback brief"
        parts.append(s)
    if "plateau_scattered" in d:
        parts.append("three probes at %.0f %% spread over %d weeks (%s): practise it more often; "
                     "a consistency problem, not a plateau"
                     % (d["plateau_mean"], d["plateau_span"], ", ".join(d["plateau_scattered"])))
    if "mastered" in r["flags"]:
        parts.append("weight to the floor %.2f, level pinned at 4" % w)
    if "lag" in d:
        parts.append("Say it lags perception by %.0f points: production_pairs %d" % (d["lag"], new_plan["production_pairs"]))
    if "untested" in r["flags"]:
        parts.append("no Say-it data yet" + (": switch production back on" if (info and new_plan["production_pairs"] == 0) else ""))
    return "; ".join(parts) if parts else "keep"


def plan_changes(old, new, rows, lag_any, info):
    """Human sentences describing new against old (the current plan.json)."""
    old = old or {}
    out = []
    reasons = {}
    for r in rows:
        tags = [f for f in FLAG_ORDER if f in r["flags"] and f != "untested"]
        if tags:
            reasons[r["contrast"]] = ", ".join(tags)
    old_w = old.get("weights") if isinstance(old.get("weights"), dict) else {}
    for c, w in new["weights"].items():
        ow = old_w.get(c)
        if is_num(ow) and abs(ow - w) < 0.005:
            continue
        why = reasons.get(c)
        if why is None:
            # no flag on this contrast: the weight can only come from
            # plan-from-ledger's score rule (a ledger, or a contrast the current
            # plan does not name), never from a silent rewrite of the plan
            why = "ledger" if info.get("from_ledger") else "not in the current plan"
        out.append("%s: weight %s -> %.2f (%s)" % (c, "%.2f" % ow if is_num(ow) else "unset", w, why))
    for key in ("feedback", "production_pairs", "production_threshold", "max_level", "trials_per_session",
                "untrained_ratio", "weekly_minutes_target"):
        if old.get(key) != new.get(key):
            why = ""
            if key == "feedback":
                why = " (plateau at level >= 3)"
            elif key == "production_pairs" and lag_any:
                why = " (Say it lags perception on %s)" % ", ".join(lag_any)
            out.append("%s: %s -> %s%s" % (key, json.dumps(old.get(key)), json.dumps(new.get(key)), why))
    old_l = old.get("levels") if isinstance(old.get("levels"), dict) else {}
    for c, lv in new["levels"].items():
        if old_l.get(c) != lv:
            out.append("levels[%s]: %s -> %d (%s)" % (c, json.dumps(old_l.get(c)), lv,
                                                       "mastered" if lv == 4 else "pinned"))
    old_b = old.get("band")
    if isinstance(old_b, list) and old_b != new["band"]:
        line = "band: %s -> %s" % (",".join(old_b), ",".join(new["band"]))
        if info.get("band_widened"):
            # the ceiling is the reason the honest probe can breathe again: say
            # it first, so it also shows in the stdout summary line
            out.insert(0, line + " (untrained_shortfall on %d of %d recent trials)"
                       % (info["shortfall"], info["trials"]))
        else:
            out.append(line)
    return out


# ------------------------------------------------------------------- report

def spark(values):
    return " ".join(pct_str(v) for v in values)


def render_markdown(a):
    c = a["consistency"]
    now = a["now"]
    lines = ["# Progress report - %s" % now.strftime("%Y-%m-%d"), ""]
    sessions = a["sessions"]
    span = ("%s to %s" % (sessions[0][0].strftime("%Y-%m-%d"), sessions[-1][0].strftime("%Y-%m-%d"))
            if sessions else "none")
    plan = a["plan"] or {}
    state = a["state"] or {}
    lines.append("Folder `%s`: %d session file(s) (%s), %d analysed weeks %s to %s. Plan written %s; "
                 "state updated %s (app %s, catalog %s, %s sessions completed)." % (
                     a["folder"], len(sessions), span, len(a["weeks"]), a["weeks"][0], a["weeks"][-1],
                     plan.get("written") or "unknown", state.get("updated") or "never",
                     state.get("app_version") or "?", state.get("catalog_version") or "?",
                     state.get("sessions_completed", "?")))
    lines += ["", "## Consistency", "",
              "| Week | Days | Sessions | Minutes | Target |", "|---|---:|---:|---:|---:|"]
    for w in c["weeks"]:
        wk = c["weekly"][w]
        mark = " (short)" if w in c["judged"] and wk["minutes"] < c["target"] else ""
        if w == c["weeks"][-1]:
            mark = " (this week)"
        lines.append("| %s | %d | %d | %.1f | %d%s |" % (w, wk["days"], wk["sessions"], wk["minutes"], c["target"], mark))
    lines += ["",
              "Current streak: %d day(s); longest %d. Average over %s: %.1f days and %.0f "
              "minutes a week against a target of %d minutes." % (
                  c["streak"], c["longest"],
                  "this week only" if c["judged"] == c["weeks"][-1:] else
                  "the completed weeks %s to %s" % (c["judged"][0], c["judged"][-1]),
                  c["mean_days"], c["mean_minutes"], c["target"]),
              "", "**Verdict:** %s." % c["verdict"], ""]
    lines += ["## Contrasts", "",
              "Untrained %% and Say it %% per week, oldest first (%s); `-` is a week without data." % " ".join(a["spark_weeks"]),
              "",
              "| Contrast | Level | Untrained % | Say it % | Flag | Recommendation |",
              "|---|---:|---|---|---|---|"]
    if not a["rows"]:
        lines.append("| (no session data) | | | | | |")
    for r in a["rows"]:
        flags = ", ".join(f for f in FLAG_ORDER if f in r["flags"]) or "-"
        lines.append("| %s | %s | %s | %s | %s | %s |" % (
            r["contrast"], "-" if r["level"] is None else r["level"], spark(r["untrained"]),
            spark(r["production"]), flags, r["recommendation"]))
    lines += ["", "## Recommended plan changes", ""]
    if a["changes"]:
        lines += ["- " + ch for ch in a["changes"]]
    else:
        lines.append("No changes against the current plan.")
    info = a["base_info"] or {}
    if info.get("band_widened"):
        lines += ["", "The honest probe ran dry on %d of %d recent trials (`untrained_shortfall`, "
                  "at or above the 10 %% mark): the recommended plan widens `band` %s -> %s "
                  "(docs/CONTRACT.md, \"untrained_shortfall\"). Lower `untrained_ratio` instead if the "
                  "wider band is not wanted." % (info["shortfall"], info["trials"],
                                                 ",".join(info["band_before"]), ",".join(a["new_plan"]["band"]))]
    elif info.get("shortfall"):
        lines += ["", "`untrained_shortfall`: %d of %d recent trials had no untrained word left anywhere in "
                  "`band` - below the 10 %% that widens the ceiling. Widen `band` or lower `untrained_ratio` "
                  "if it grows." % (info["shortfall"], info["trials"])]
    lonely = [r["contrast"] for r in a["rows"] if r["detail"].get("lonely")]
    if lonely:
        lines += ["", "The regression on %s fell in a week with a single session: read it as a consistency "
                  "problem first (docs/ADAPTATION.md, Consistency)." % ", ".join(lonely)]
    scattered = [r["contrast"] for r in a["rows"] if r["detail"].get("plateau_scattered")]
    if scattered:
        lines += ["", "Three flat probes on %s are spread over more than %d weeks, so they are not counted "
                  "as a plateau: practise those contrasts more regularly first (docs/ADAPTATION.md, "
                  "Consistency)." % (", ".join(scattered), PLATEAU_MAX_SPAN)]
    lines.append("")
    return "\n".join(lines)


def summary_lines(a, out_path, plan_path):
    c = a["consistency"]
    flags = {f: [r["contrast"] for r in a["rows"] if f in r["flags"]] for f in FLAG_ORDER}
    latest = [r for r in a["rows"] if r["last_untrained"] is not None]
    perc = ", ".join("%s %s" % (r["contrast"], pct_str(r["last_untrained"])) for r in latest) or "none"
    prod = ", ".join("%s %s" % (r["contrast"], pct_str(r["last_production"]))
                     for r in a["rows"] if r["last_production"] is not None) or "none"
    wrote = [p for p in (out_path, plan_path) if p]
    return [
        "Progress report %s: %d session file(s), weeks %s to %s" % (
            a["now"].strftime("%Y-%m-%d"), len(a["sessions"]), a["weeks"][0], a["weeks"][-1]),
        "Consistency: %.1f days and %.0f min a week vs %d; streak %d (longest %d); %s" % (
            c["mean_days"], c["mean_minutes"], c["target"], c["streak"], c["longest"], c["verdict"]),
        "Last probe untrained %%: %s | last Say it %%: %s" % (perc, prod),
        "Flags: " + "; ".join("%s %s" % (f, ", ".join(v) if v else "-") for f, v in flags.items()),
        "Plan: %d change(s)%s%s" % (len(a["changes"]),
                                     " - " + "; ".join(a["changes"][:3]) + (" ..." if len(a["changes"]) > 3 else "")
                                     if a["changes"] else "",
                                     "; wrote " + ", ".join(wrote) if wrote else ""),
    ]


# ----------------------------------------------------------------- selftest

def _session(sid, started, probes, production=None, levels=None, duration=180, shortfall=0):
    """probes: [(contrast, untrained trials, untrained correct)]; production:
    [(contrast, points)]. Rows are shaped like the contract's; the summary is
    computed from them."""
    trials = []
    for contrast, n, k in probes:
        for j in range(n):
            ok = j < k
            trials.append({"i": len(trials) + 1, "contrast": contrast, "pair": contrast + ":a-b",
                           "target": "a", "other": "b", "chosen": "a" if ok else "b", "correct": ok,
                           "voice": "en-GB-SoniaNeural", "rt_ms": 900, "replays": 0, "trained": False,
                           "band": "high", "position": "initial"})
    prod = None
    if production is not None:
        prod = [{"i": i, "contrast": c, "pair": c + ":a-b", "a": "a", "b": "b", "points": pts,
                 "level": (levels or {}).get(c, 1), "words": {}}
                for i, (c, pts) in enumerate(production, 1)]
    n = len(trials)
    k = sum(1 for t in trials if t["correct"])
    ended = PFL.parse_ts(started) + _dt.timedelta(seconds=duration)
    return {"version": 1, "id": sid, "started": started, "ended": ended.strftime("%Y-%m-%dT%H:%M:%SZ"),
            "app_version": "0.1.0", "catalog_version": "t", "plan_source": "coach", "plan_written": None,
            "voices": ["en-GB-SoniaNeural"], "trials": trials, "levels": levels or {}, "production": prod,
            "summary": {"trials": n, "correct": k, "pct": k / n if n else 0.0, "untrained_trials": n,
                        "untrained_correct": k, "untrained_pct": k / n if n else None,
                        "duration_s": duration, "mean_rt_ms": 900, "untrained_shortfall": shortfall,
                        "contrasts": {}, "production": None}}


def _write(folder, plan, state, sessions):
    os.makedirs(os.path.join(folder, "sessions"), exist_ok=True)
    if plan is not None:
        with open(os.path.join(folder, "plan.json"), "w", encoding="utf-8") as f:
            json.dump(plan, f)
    if state is not None:
        with open(os.path.join(folder, "state.json"), "w", encoding="utf-8") as f:
            json.dump(state, f)
    for s in sessions:
        with open(os.path.join(folder, "sessions", s["id"] + ".json"), "w", encoding="utf-8") as f:
            json.dump(s, f)


def selftest():
    now = _dt.datetime(2026, 9, 11, 12, 0, 0, tzinfo=_dt.timezone.utc)
    assert last_weeks(now, 4) == ["2026-W34", "2026-W35", "2026-W36", "2026-W37"], last_weeks(now, 4)
    assert streaks({"2026-09-09": {}, "2026-09-10": {}, "2026-09-11": {}, "2026-09-01": {}}, now) == (3, 3)
    assert streaks({"2026-09-05": {}}, now) == (0, 1)
    plan = {"version": 1, "written": "2026-09-01T18:00:00Z", "written_by": "coach", "note": "keep going",
            "trials_per_session": 30, "untrained_ratio": 0.5, "voices": ["en-GB-SoniaNeural"],
            "band": ["high", "mid", "low"], "feedback": "full",
            "production_pairs": 8, "production_threshold": 60, "max_level": 4, "levels": {},
            "weekly_minutes_target": 20,
            "weights": {"th": 0.6, "s/z": 0.6, "i/ii": 0.6, "b/v": 0.6, "cat/cut": 0.6}}
    lv = {"th": 2, "s/z": 3, "i/ii": 3, "b/v": 1, "cat/cut": 2}
    sessions = [
        # W34: th 80, s/z 70, i/ii 100 (+ Say it 100), b/v 50, cat/cut 100 (+ Say it 50)
        _session("20260818T070000Z", "2026-08-18T07:00:00Z",
                 [("th", 10, 8), ("s/z", 10, 7), ("i/ii", 10, 10), ("b/v", 10, 5), ("cat/cut", 10, 10)],
                 [("i/ii", 2), ("i/ii", 2), ("cat/cut", 1), ("cat/cut", 1)], lv, 600),
        _session("20260820T070000Z", "2026-08-20T07:00:00Z", [("th", 10, 8)], [], lv, 600),
        # W35: th 80, s/z 75, i/ii 100 (+100), b/v 60, cat/cut 100 (+25)
        _session("20260825T070000Z", "2026-08-25T07:00:00Z",
                 [("th", 10, 8), ("s/z", 4, 3), ("i/ii", 10, 10), ("b/v", 10, 6), ("cat/cut", 10, 10)],
                 [("i/ii", 2), ("i/ii", 2), ("cat/cut", 1), ("cat/cut", 0)], lv, 600),
        _session("20260827T070000Z", "2026-08-27T07:00:00Z", [("b/v", 10, 6)], [], lv, 600),
        _session("20260829T070000Z", "2026-08-29T07:00:00Z", [("b/v", 10, 6)], [], lv, 600),
        # W36: s/z 65, i/ii 95 (+87.5), b/v 50, cat/cut 100 (+50); no th probe
        _session("20260901T070000Z", "2026-09-01T07:00:00Z",
                 [("s/z", 20, 13), ("i/ii", 20, 19), ("b/v", 10, 5), ("cat/cut", 10, 10)],
                 [("i/ii", 2), ("i/ii", 2), ("i/ii", 2), ("i/ii", 1), ("cat/cut", 1), ("cat/cut", 1)], lv, 600),
        _session("20260903T070000Z", "2026-09-03T07:00:00Z", [("b/v", 10, 5)], [], lv, 600),
        _session("20260905T070000Z", "2026-09-05T07:00:00Z", [("b/v", 10, 5)], [], lv, 600),
        # W37 (this week): th 60 in the only session of the week -> regression, lonely
        _session("20260909T070000Z", "2026-09-09T07:00:00Z", [("th", 10, 6), ("cat/cut", 10, 10)],
                 [("cat/cut", 1), ("cat/cut", 0)], lv, 300),
    ]
    with tempfile.TemporaryDirectory() as tmp:
        folder = os.path.join(tmp, "f")
        _write(folder, plan, None, sessions)
        out_md = os.path.join(tmp, "report.md")
        out_plan = os.path.join(tmp, "plan-new.json")
        a = analyse(folder, 6, now, plan_out=out_plan)
        flags = {r["contrast"]: r["flags"] for r in a["rows"]}
        assert flags == {"th": {"regression", "untested"}, "s/z": {"plateau", "untested"},
                         "i/ii": {"mastered"}, "b/v": {"untested"}, "cat/cut": set()}, flags
        det = {r["contrast"]: r["detail"] for r in a["rows"]}
        assert det["th"]["drop"] == 20.0 and det["th"]["lonely"] and det["th"]["regression_week"] == "2026-W37"
        assert det["s/z"]["plateau_weeks"] == ["2026-W34", "2026-W35", "2026-W36"], det["s/z"]
        assert det["s/z"]["plateau_level"] == 3 and abs(det["s/z"]["plateau_mean"] - 70) < 1e-9
        assert "lag" in det["cat/cut"] and abs(det["cat/cut"]["lag"] - 62.5) < 1e-9, det["cat/cut"]
        assert a["lag"] == ["cat/cut"]
        levels = {r["contrast"]: r["level"] for r in a["rows"]}
        assert levels == lv, levels
        # sparkline: last four weeks W34..W37
        by = {r["contrast"]: r for r in a["rows"]}
        assert by["th"]["untrained"] == [80.0, 80.0, None, 60.0], by["th"]["untrained"]
        assert by["i/ii"]["production"] == [100.0, 100.0, 87.5, None], by["i/ii"]["production"]
        assert by["b/v"]["production"] == [None] * 4

        # the plan: the current plan's weights, moved only where the evidence says so
        base = a["base_info"]["base_weights"]
        new = a["new_plan"]
        assert new["weights"]["th"] == PFL.round2(min(1.0, 0.6 * PFL.REGRESSION_FACTOR)) == 0.9, new["weights"]["th"]
        assert "th" in a["base_info"]["regressed"]
        assert new["weights"]["s/z"] == PFL.round2(min(1.0, 0.6 * PLATEAU_FACTOR)) == 0.75, new["weights"]["s/z"]
        assert new["weights"]["i/ii"] == PFL.FLOOR and new["levels"] == {"i/ii": 4}, new
        # no flag and named by the plan: the weight is left exactly as it was,
        # even though plan-from-ledger's score rule would have moved it
        assert new["weights"]["b/v"] == 0.6 and new["weights"]["cat/cut"] == 0.6, new["weights"]
        assert base["b/v"] != 0.6 and base["cat/cut"] != 0.6, base
        assert a["base_info"]["seeded"] == {"th", "s/z", "i/ii", "b/v", "cat/cut"}, a["base_info"]["seeded"]
        # a contrast the plan does not name is filled in from plan-from-ledger
        assert new["weights"]["j/y"] == base["j/y"] == PFL.FLOOR, new["weights"]["j/y"]
        assert new["feedback"] == "brief"                       # plateau at level 3
        assert new["production_pairs"] == 12                    # lag on cat/cut
        assert new["trials_per_session"] == 30 and new["note"] == "keep going"   # carried over
        assert new["band"] == ["high", "mid", "low"] and new["weekly_minutes_target"] == 20
        changes = "\n".join(a["changes"])
        assert "i/ii: weight 0.60 -> 0.15 (mastered)" in changes, changes
        assert "levels[i/ii]: null -> 4 (mastered)" in changes, changes
        assert 'feedback: "full" -> "brief" (plateau at level >= 3)' in changes, changes
        assert "production_pairs: 8 -> 12 (Say it lags perception on cat/cut)" in changes, changes
        assert "th: weight 0.60 -> 0.90 (regression)" in changes, changes
        assert "s/z: weight 0.60 -> 0.75 (plateau)" in changes, changes
        assert "b/v: weight" not in changes and "cat/cut: weight" not in changes, changes
        assert "j/y: weight unset -> 0.15 (not in the current plan)" in changes, changes
        rec = {r["contrast"]: r["recommendation"] for r in a["rows"]}
        assert "consistency problem" in rec["th"] and "feedback brief" in rec["s/z"], rec
        assert rec["cat/cut"].startswith("Say it lags perception by 62 points"), rec
        assert rec["b/v"] == "no Say-it data yet", rec

        # consistency: 6 weeks W32..W37, sessions from files (no state.json)
        c = a["consistency"]
        assert c["weeks"][0] == "2026-W32" and c["weekly"]["2026-W35"]["days"] == 3
        assert abs(c["weekly"]["2026-W35"]["minutes"] - 30.0) < 1e-9 and c["weekly"]["2026-W32"]["sessions"] == 0
        assert c["streak"] == 0 and c["longest"] == 1
        assert c["judged"] == ["2026-W34", "2026-W35", "2026-W36"], c["judged"]
        assert abs(c["mean_days"] - 8 / 3.0) < 1e-9 and abs(c["mean_minutes"] - 80 / 3.0) < 1e-9
        assert c["verdict"].startswith("minutes on target (27 of 20) but bunched into 2.7 days"), c["verdict"]
        assert "Average over the completed weeks 2026-W34 to 2026-W36" in render_markdown(a)

        # report and plan files
        md = render_markdown(a)
        with open(out_md, "w", encoding="utf-8") as f:
            f.write(md)
        PFL.write_plan(new, out_plan)
        with open(out_plan, encoding="utf-8") as f:
            back = json.load(f)
        assert back == new
        assert "## Consistency" in md and "## Contrasts" in md and "## Recommended plan changes" in md
        assert "| th | 2 | 80 80 - 60 | - - - - | regression, untested |" in md, md
        assert "| i/ii | 3 | 100 100 95 - | 100 100 88 - | mastered |" in md, md
        assert "single session" in md
        lines = summary_lines(a, out_md, out_plan)
        assert len(lines) == 5 and lines[3].startswith("Flags: regression th; plateau s/z; mastered i/ii; untested th, s/z, b/v"), lines

        # state.json wins for streak, longest streak and practice days; a clean
        # history (no flags) recommends no weight change beyond the evidence
        state = {"version": 1, "streak_days": 4, "contrasts": {"th": {"level": 4}},
                 "practice": {"longest_streak": 9, "total_seconds": 0,
                              "days": {"2026-09-08": {"sessions": 2, "seconds": 1200},
                                       "2026-09-09": {"sessions": 1, "seconds": 600}}}}
        with open(os.path.join(folder, "state.json"), "w", encoding="utf-8") as f:
            json.dump(state, f)
        a2 = analyse(folder, 2, now)
        assert a2["consistency"]["streak"] == 4 and a2["consistency"]["longest"] == 9
        assert a2["consistency"]["weekly"]["2026-W37"] == {"days": 2, "sessions": 3, "minutes": 30.0}
        assert {r["contrast"]: r["level"] for r in a2["rows"]}["th"] == 4
        assert a2["consistency"]["weekly"]["2026-W36"]["sessions"] == 0     # state days only
        assert a2["consistency"]["judged"] == ["2026-W37"]                    # nothing completed: this week
        assert a2["consistency"]["verdict"] == "on target: 30 min over 2.0 days a week (target 20 min) (only this week has practice so far)" or \
            a2["consistency"]["verdict"].endswith("(only this week has practice so far)"), a2["consistency"]["verdict"]

        clean = os.path.join(tmp, "clean")
        _write(clean, plan, None, [
            _session("20260901T070000Z", "2026-09-01T07:00:00Z", [("th", 10, 9)], [("th", 2)], lv),
            _session("20260903T070000Z", "2026-09-03T07:00:00Z", [("th", 10, 9)], [("th", 2)], lv),
            _session("20260909T070000Z", "2026-09-09T07:00:00Z", [("th", 10, 8)], [("th", 1)], lv),
        ])
        a3 = analyse(clean, 6, now)
        assert [r["flags"] for r in a3["rows"]] == [set()], a3["rows"]
        # no flag anywhere: every contrast the plan names keeps its weight, only
        # the ones it does not name are filled in (never a wholesale collapse)
        named = ["th", "s/z", "i/ii", "b/v", "cat/cut"]
        assert all(a3["new_plan"]["weights"][c] == 0.6 for c in named), a3["new_plan"]["weights"]
        assert a3["base_info"]["base_weights"]["s/z"] == PFL.FLOOR    # what the score rule alone would say
        assert not [ch for ch in a3["changes"] if ch.split(":")[0] in named], a3["changes"]
        assert a3["new_plan"]["feedback"] == "full" and a3["new_plan"]["production_pairs"] == 8
        assert a3["new_plan"]["levels"] == {}
        assert [r["recommendation"] for r in a3["rows"]] == ["keep"]
        # with a ledger the coach's own counts are the evidence: plan-from-ledger's
        # weights are kept for every contrast, the plan's are not carried over
        ledger_path = os.path.join(tmp, "ledger.json")
        with open(ledger_path, "w", encoding="utf-8") as f:
            json.dump({"phonemes": {"th": {"count": 20}, "b/v": {"count": 10}}}, f)
        a3b = analyse(clean, 6, now, ledger=ledger_path)
        assert a3b["base_info"]["seeded"] == set()
        assert a3b["new_plan"]["weights"] == a3b["base_info"]["base_weights"], a3b["new_plan"]["weights"]
        assert a3b["new_plan"]["weights"]["th"] == 1.0 and a3b["new_plan"]["weights"]["b/v"] > PFL.FLOOR
        assert "b/v: weight 0.60 -> %.2f (ledger)" % a3b["new_plan"]["weights"]["b/v"] in "\n".join(a3b["changes"]), a3b["changes"]
        # production switched off while a contrast is untested -> back on
        plan_off = dict(plan, production_pairs=0)
        off = os.path.join(tmp, "off")
        _write(off, plan_off, None, [_session("20260909T070000Z", "2026-09-09T07:00:00Z", [("th", 10, 8)], None, lv)])
        a4 = analyse(off, 6, now)
        assert a4["rows"][0]["flags"] == {"untested"} and a4["new_plan"]["production_pairs"] == 8
        assert "production_pairs: 0 -> 8" in "\n".join(a4["changes"])
        # the honest probe running dry: the recommended band widens even though
        # the current plan names a narrower one (docs/CONTRACT.md, untrained_shortfall)
        narrow = dict(plan, band=["high", "mid"])
        dry = os.path.join(tmp, "dry")
        _write(dry, narrow, None, [_session("20260909T070000Z", "2026-09-09T07:00:00Z",
                                            [("th", 10, 8)], [("th", 2)], lv, 300, shortfall=2)])
        a6 = analyse(dry, 6, now)
        assert a6["base_info"]["shortfall"] == 2 and a6["base_info"]["trials"] == 10
        assert a6["base_info"]["band_widened"] and a6["base_info"]["band_before"] == ["high", "mid"]
        assert a6["new_plan"]["band"] == ["high", "mid", "low"], a6["new_plan"]["band"]
        assert a6["changes"][0] == "band: high,mid -> high,mid,low (untrained_shortfall on 2 of 10 recent trials)", a6["changes"]
        md6 = render_markdown(a6)
        assert "widens `band` high,mid -> high,mid,low" in md6, md6
        assert "band: high,mid -> high,mid,low" in summary_lines(a6, None, None)[4]
        # below the 10 % mark the band is carried over untouched
        wet = os.path.join(tmp, "wet")
        _write(wet, narrow, None, [_session("20260909T070000Z", "2026-09-09T07:00:00Z",
                                            [("th", 20, 16)], [("th", 2)], lv, 300, shortfall=1)])
        a7 = analyse(wet, 6, now)
        assert not a7["base_info"].get("band_widened") and a7["new_plan"]["band"] == ["high", "mid"]
        assert not [ch for ch in a7["changes"] if ch.startswith("band:")], a7["changes"]

        # three flat probes spread over eight weeks are a consistency problem,
        # not a plateau; three weeks with at most one gap still are one
        scattered = os.path.join(tmp, "scattered")
        _write(scattered, plan, None, [
            _session("20260720T070000Z", "2026-07-20T07:00:00Z", [("s/z", 10, 7)], [("s/z", 2)], lv),
            _session("20260804T070000Z", "2026-08-04T07:00:00Z", [("s/z", 10, 7)], [("s/z", 2)], lv),
            _session("20260909T070000Z", "2026-09-09T07:00:00Z", [("s/z", 10, 7)], [("s/z", 2)], lv),
        ])
        a8 = analyse(scattered, 10, now)
        r8 = a8["rows"][0]
        assert r8["flags"] == set(), r8["flags"]
        assert r8["detail"]["plateau_scattered"] == ["2026-W30", "2026-W32", "2026-W37"], r8["detail"]
        assert r8["detail"]["plateau_span"] == 8 and r8["detail"]["plateau_mean"] == 70.0
        assert "consistency problem, not a plateau" in r8["recommendation"], r8["recommendation"]
        assert a8["new_plan"]["weights"]["s/z"] == 0.6      # no plateau, no weight change
        assert "not counted as a plateau" in render_markdown(a8)
        gapped = os.path.join(tmp, "gapped")
        _write(gapped, plan, None, [
            _session("20260818T070000Z", "2026-08-18T07:00:00Z", [("s/z", 10, 7)], [("s/z", 2)], lv),
            _session("20260901T070000Z", "2026-09-01T07:00:00Z", [("s/z", 10, 7)], [("s/z", 2)], lv),
            _session("20260909T070000Z", "2026-09-09T07:00:00Z", [("s/z", 10, 7)], [("s/z", 2)], lv),
        ])
        a9 = analyse(gapped, 10, now)
        assert a9["rows"][0]["flags"] == {"plateau"}, a9["rows"][0]
        assert a9["rows"][0]["detail"]["plateau_weeks"] == ["2026-W34", "2026-W36", "2026-W37"]
        assert a9["new_plan"]["weights"]["s/z"] == PFL.round2(0.6 * PLATEAU_FACTOR)

        # an empty folder still renders
        empty = os.path.join(tmp, "empty")
        os.makedirs(empty)
        a5 = analyse(empty, 3, now)
        assert a5["rows"] == [] and a5["consistency"]["verdict"].startswith("no practice")
        assert "(no session data)" in render_markdown(a5)
        assert len(summary_lines(a5, None, None)) == 5
    print("progress-report selftest: OK", file=sys.stderr)


def parse_now(s):
    d = PFL.parse_ts(s)
    if d is None:
        raise SystemExit("--now must be a UTC timestamp like 2026-09-11T12:00:00Z")
    return d


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    p.add_argument("folder", nargs="?", help="a folder laid out like Documents/MinimalPairs")
    p.add_argument("--weeks", type=int, default=DEFAULT_WEEKS, help="ISO weeks to report (default 6)")
    p.add_argument("--out", help="write the Markdown report here")
    p.add_argument("--plan-out", help="write the recommended plan.json here")
    p.add_argument("--ledger", help="coach ledger for plan-from-ledger's weights")
    p.add_argument("--catalog", default=None, help="catalog.json (default: the repo's)")
    p.add_argument("--note", default=None, help="plan.note for the written plan (default: the current plan's)")
    p.add_argument("--now", default=None, help="report date, UTC timestamp (default: now)")
    p.add_argument("--selftest", action="store_true")
    args = p.parse_args(sys.argv[1:] if argv is None else argv)
    if args.selftest:
        selftest()
        return 0
    if not args.folder:
        p.error("folder is required (or use --selftest)")
    if not os.path.isdir(args.folder):
        raise SystemExit("not a directory: " + args.folder)
    if args.weeks < 1:
        raise SystemExit("--weeks must be >= 1")
    now = parse_now(args.now) if args.now else _dt.datetime.now(_dt.timezone.utc).replace(microsecond=0)
    a = analyse(args.folder, args.weeks, now, catalog=args.catalog, ledger=args.ledger,
                note=args.note, plan_out=args.plan_out)
    md = render_markdown(a)
    if args.out:
        with open(args.out, "w", encoding="utf-8") as f:
            f.write(md)
    if args.plan_out:
        PFL.write_plan(a["new_plan"], args.plan_out)
    for line in summary_lines(a, args.out, args.plan_out):
        print(line)
    if not args.out:
        print("")
        print(md)
    return 0


if __name__ == "__main__":
    sys.exit(main())
