#!/usr/bin/env python3
"""Turn the coach's pronunciation ledger plus recent session files into plan.json.

Usage:
  plan-from-ledger.py --ledger logs/pronunciation-ledger.json \
                      --sessions Documents/MinimalPairs/sessions \
                      --out Documents/MinimalPairs/plan.json [options]
  plan-from-ledger.py --selftest

Options:
  --ledger PATH        coach ledger {"phonemes": {"<class>": {"count": n, "words": {...}}}}
                       (a missing file or missing keys count as no evidence)
  --sessions DIR       folder of session files per docs/CONTRACT.md (optional)
  --catalog PATH       data/catalog/catalog.json for the contrast list and default
                       weights (default: the repo's; a hardcoded list is used if absent)
  --out PATH           where to write plan.json (required unless --selftest)
  --days N             recent window for session misses, default 14
  --note TEXT          plan.note shown on the learner's Home screen
  --trials N           plan.trials_per_session (10-120, default 40)
  --untrained-ratio F  plan.untrained_ratio (0-1, default 0.5)
  --band a,b           plan.band, the ceiling of word bands; default high,mid,low
                       (the level ladder picks within it, docs/ADAPTATION.md). When
                       the recent sessions report untrained_shortfall on at least
                       10 % of their trials (the honest probe has run out of
                       untrained words, docs/CONTRACT.md) a narrower default would
                       widen to high,mid,low; an explicit --band is kept and a loud
                       warning is printed instead (lower --untrained-ratio or widen).
  --feedback LEVEL     full | brief | minimal (default full)
  --voices a,b         plan.voices (default: every voice of the catalog)
  --production-pairs N       plan.production_pairs (0-30, default 8; 0 = Say it off)
  --production-threshold N   plan.production_threshold (0-100, default 60)
  --max-level N        plan.max_level (1-4, default 4)
  --pin CONTRAST=LEVEL plan.levels: pin a contrast's level (repeatable)
  --weekly-minutes N   plan.weekly_minutes_target (0-300, default 20)
  --selftest           run the built-in checks on synthetic data and exit

Algorithm (docs/CONTRACT.md, "Coach side"):
  score(c)  = ledger_count(c) + 2 * recent_misses(c) + 3 * production_misses(c)
  weight(c) = 0.15 + 0.85 * score(c) / max_score          rounded to 2 decimals
  recent_misses(c): incorrect untrained trials on c in sessions started within
  the last --days days. production_misses(c): Say-it pairs on c that scored
  below 2 in the same window (the ledger's own class of evidence: the mouth).
  Regression: a contrast whose untrained percent in the last session that
  probed it dropped >= 15 points against the mean of the previous two probes
  gets its weight x 1.5, capped at 1.0 (all sessions in the folder count, so a
  regression stays visible until a later probe recovers).
  Production-only contrasts (s-cluster) get weight 0. With no evidence at all
  (max_score == 0) the catalog default weights are used, still subject to the
  regression rule. Ledger class names equal contrast ids.

Standard library only, Python 3.9+.
"""

import argparse
import datetime as _dt
import json
import os
import sys
import tempfile

FLOOR = 0.15
SPAN = 1.0 - FLOOR
MISS_WEIGHT = 2             # one incorrect untrained trial
PRODUCTION_MISS_WEIGHT = 3  # one Say-it pair scored below 2
REGRESSION_DROP = 15.0      # points of untrained percent, last probe vs mean of previous two
REGRESSION_FACTOR = 1.5
DEFAULT_PRODUCTION_PAIRS = 8
DEFAULT_PRODUCTION_THRESHOLD = 60
DEFAULT_MAX_LEVEL = 4
DEFAULT_WEEKLY_MINUTES = 20
# Share of the window's trials that were meant to be untrained but had no
# untrained word left (summary.untrained_shortfall) above which the probe is
# considered collapsed: widen the band (default) or warn (explicit --band).
SHORTFALL_WARN = 0.10
WIDE_BAND = ["high", "mid", "low"]
DEFAULT_BAND = list(WIDE_BAND)

# Fallback contrast list (docs/DESIGN.md, "Catalog") used only when the catalog
# file cannot be read.
FALLBACK_CONTRASTS = [
    ("th", 1.0, False), ("s/z", 0.8, False), ("i/ii", 0.6, False),
    ("b/v", 0.5, False), ("cat/cut", 0.4, False), ("long-back", 0.3, False),
    ("j/y", 0.3, False), ("-ed", 0.3, False), ("h", 0.2, False),
    ("sh/ch", 0.2, False), ("er/or", 0.2, False), ("schwa", 0.15, False),
    ("s-cluster", 0.0, True),
]

FALLBACK_VOICES = [
    "en-GB-SoniaNeural", "en-GB-LibbyNeural", "en-GB-HollieNeural",
    "en-GB-RyanNeural", "en-GB-ThomasNeural", "en-GB-AlfieNeural",
]

BANDS = ("high", "mid", "low")
FEEDBACK = ("full", "brief", "minimal")


def default_catalog_path():
    here = os.path.dirname(os.path.abspath(__file__))
    return os.path.join(os.path.dirname(here), "data", "catalog", "catalog.json")


def warn(msg):
    print("plan-from-ledger: " + msg, file=sys.stderr)


def load_json(path):
    """Return the parsed file or None (missing / unreadable / not JSON)."""
    if not path or not os.path.isfile(path):
        return None
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError) as e:
        warn("cannot read %s: %s" % (path, e))
        return None


def load_contrasts(catalog_path):
    """Return ([(id, default_weight, production_only)], voices)."""
    cat = load_json(catalog_path)
    if isinstance(cat, dict) and isinstance(cat.get("contrasts"), list):
        out = []
        for c in cat["contrasts"]:
            if not isinstance(c, dict) or not isinstance(c.get("id"), str):
                continue
            try:
                dw = float(c.get("default_weight", 0.0))
            except (TypeError, ValueError):
                dw = 0.0
            po = bool(c.get("production_only", False)) or not c.get("trainable", True)
            out.append((c["id"], clamp(dw, 0.0, 1.0), po))
        voices = cat.get("voices")
        if not (isinstance(voices, list) and voices and all(isinstance(v, str) for v in voices)):
            voices = list(FALLBACK_VOICES)
        if out:
            return out, voices
    if catalog_path:
        warn("catalog %s not usable, using the built-in contrast list" % catalog_path)
    return list(FALLBACK_CONTRASTS), list(FALLBACK_VOICES)


def ledger_counts(ledger):
    """{class: count} from the ledger, tolerating any missing or odd keys."""
    counts = {}
    if not isinstance(ledger, dict):
        return counts
    phonemes = ledger.get("phonemes")
    if not isinstance(phonemes, dict):
        return counts
    for name, entry in phonemes.items():
        n = 0
        if isinstance(entry, dict):
            n = entry.get("count", 0)
        elif isinstance(entry, (int, float)):
            n = entry
        try:
            n = int(n)
        except (TypeError, ValueError):
            n = 0
        counts[str(name)] = max(0, n)
    return counts


def parse_ts(s):
    """Parse a contract timestamp (2026-09-11T07:02:11Z) into an aware datetime."""
    if not isinstance(s, str):
        return None
    try:
        if s.endswith("Z"):
            s = s[:-1] + "+00:00"
        d = _dt.datetime.fromisoformat(s)
    except ValueError:
        return None
    if d.tzinfo is None:
        d = d.replace(tzinfo=_dt.timezone.utc)
    return d.astimezone(_dt.timezone.utc)


def load_sessions(sessions_dir):
    """[(started, session)] sorted by start time, for every parsable session file
    (a dict with a "trials" list and a valid "started"); [] when no folder."""
    out = []
    if not sessions_dir or not os.path.isdir(sessions_dir):
        return out
    for name in sorted(os.listdir(sessions_dir)):
        if not name.endswith(".json"):
            continue
        s = load_json(os.path.join(sessions_dir, name))
        if not isinstance(s, dict) or not isinstance(s.get("trials"), list):
            continue
        started = parse_ts(s.get("started"))
        if started is None:
            continue
        out.append((started, s))
    out.sort(key=lambda x: x[0])
    return out


def _is_int(x):
    return isinstance(x, int) and not isinstance(x, bool)


def session_contrast_stats(session):
    """{contrast: stats} for one session, from its rows (not its summary):
    trials, correct, untrained_trials, untrained_correct, misses (incorrect
    untrained trials), prod_pairs, prod_points, prod_misses (pairs scored < 2),
    level (session.levels[c] or None). Contrasts appear when they have a trial
    or a Say-it pair."""
    out = {}

    def get(c):
        return out.setdefault(c, {"trials": 0, "correct": 0, "untrained_trials": 0,
                                  "untrained_correct": 0, "misses": 0, "prod_pairs": 0,
                                  "prod_points": 0, "prod_misses": 0, "level": None})

    for t in session.get("trials") or []:
        if not isinstance(t, dict) or not isinstance(t.get("contrast"), str):
            continue
        g = get(t["contrast"])
        ok = t.get("correct") is True
        g["trials"] += 1
        g["correct"] += 1 if ok else 0
        if t.get("trained") is False:
            g["untrained_trials"] += 1
            g["untrained_correct"] += 1 if ok else 0
            g["misses"] += 0 if ok else 1
    prod = session.get("production")
    if isinstance(prod, list):
        for row in prod:
            if not isinstance(row, dict) or not isinstance(row.get("contrast"), str):
                continue
            g = get(row["contrast"])
            pts = row.get("points")
            pts = pts if _is_int(pts) and 0 <= pts <= 2 else 0
            g["prod_pairs"] += 1
            g["prod_points"] += pts
            g["prod_misses"] += 1 if pts < 2 else 0
    levels = session.get("levels")
    if isinstance(levels, dict):
        for c, g in out.items():
            lv = levels.get(c)
            if _is_int(lv) and 1 <= lv <= 4:
                g["level"] = lv
    return out


def recent_misses(sessions, days, now):
    """({contrast: incorrect untrained trials}, {contrast: Say-it pairs < 2},
    sessions used, shortfall, trials) over the sessions started within the
    window. shortfall is the sum of summary.untrained_shortfall, trials the
    number of trial rows."""
    misses = {}
    prod_misses = {}
    cutoff = now - _dt.timedelta(days=days)
    used = 0
    shortfall = 0
    trials_total = 0
    for started, s in sessions:
        if started < cutoff:
            continue
        used += 1
        summary = s.get("summary")
        if isinstance(summary, dict):
            sf = summary.get("untrained_shortfall", 0)
            if _is_int(sf) and sf > 0:
                shortfall += sf
        for c, g in session_contrast_stats(s).items():
            trials_total += g["trials"]
            if g["misses"]:
                misses[c] = misses.get(c, 0) + g["misses"]
            if g["prod_misses"]:
                prod_misses[c] = prod_misses.get(c, 0) + g["prod_misses"]
    return misses, prod_misses, used, shortfall, trials_total


def probe_series(sessions):
    """{contrast: [(started, untrained percent 0-100, level)]} over every session
    that probed the contrast with at least one untrained trial, oldest first."""
    series = {}
    for started, s in sessions:
        for c, g in session_contrast_stats(s).items():
            if g["untrained_trials"]:
                series.setdefault(c, []).append(
                    (started, 100.0 * g["untrained_correct"] / g["untrained_trials"], g["level"]))
    return series


def regression_drop(pcts):
    """Points the last percent dropped against the mean of the previous two
    (positive = worse), or None with fewer than three values."""
    if len(pcts) < 3:
        return None
    return (pcts[-3] + pcts[-2]) / 2.0 - pcts[-1]


def regressions(sessions):
    """{contrast: drop in points} for contrasts whose last probe dropped >= 15
    points against the mean of the previous two probes."""
    out = {}
    for c, rows in probe_series(sessions).items():
        drop = regression_drop([r[1] for r in rows])
        if drop is not None and drop >= REGRESSION_DROP:
            out[c] = drop
    return out


def clamp(x, lo, hi):
    return lo if x < lo else hi if x > hi else x


def round2(x):
    """Round half up to 2 decimals (0.575 -> 0.58), immune to float noise."""
    return round(x + 1e-9, 2)


def compute_weights(contrasts, counts, misses, prod_misses=None, regressed=None):
    """Return ({id: weight}, {id: score}, used_defaults). regressed is the set of
    contrasts under the regression rule (weight x 1.5, capped at 1.0)."""
    prod_misses = prod_misses or {}
    regressed = regressed or ()
    scores = {}
    for cid, _dw, po in contrasts:
        if po:
            scores[cid] = 0
        else:
            scores[cid] = (counts.get(cid, 0) + MISS_WEIGHT * misses.get(cid, 0)
                           + PRODUCTION_MISS_WEIGHT * prod_misses.get(cid, 0))
    max_score = max(scores.values()) if scores else 0
    weights = {}
    used_defaults = max_score <= 0
    for cid, dw, po in contrasts:
        if po:
            weights[cid] = 0.0
            continue
        w = dw if used_defaults else FLOOR + SPAN * scores[cid] / max_score
        if cid in regressed:
            w = min(1.0, w * REGRESSION_FACTOR)
        weights[cid] = round2(w)
    return weights, scores, used_defaults


def parse_pins(pins, contrasts):
    """{contrast: level} from repeated --pin CONTRAST=LEVEL values."""
    out = {}
    known = {c[0] for c in contrasts}
    for item in pins or []:
        if "=" not in item:
            raise SystemExit("--pin expects CONTRAST=LEVEL, got %r" % item)
        cid, lv = item.rsplit("=", 1)
        cid = cid.strip()
        try:
            lv = int(lv)
        except ValueError:
            raise SystemExit("--pin %s: level must be an integer 1-4" % item)
        if not 1 <= lv <= 4:
            raise SystemExit("--pin %s: level must be 1-4" % item)
        if cid not in known:
            warn("--pin %s: unknown contrast id (the app ignores it)" % item)
        out[cid] = lv
    return out


def build_plan(args, now):
    contrasts, cat_voices = load_contrasts(args.catalog)
    counts = ledger_counts(load_json(args.ledger))
    sessions = load_sessions(args.sessions)
    misses, prod_misses, n_sessions, shortfall, trials_total = recent_misses(sessions, args.days, now)
    regressed = regressions(sessions)
    weights, scores, used_defaults = compute_weights(contrasts, counts, misses, prod_misses, regressed)

    band_given = bool(args.band and args.band.strip())
    band = [b.strip() for b in (args.band or ",".join(DEFAULT_BAND)).split(",") if b.strip()]
    bad = [b for b in band if b not in BANDS]
    if bad:
        raise SystemExit("--band: unknown band(s) %s (allowed: %s)" % (", ".join(bad), ", ".join(BANDS)))
    if not band:
        band = list(DEFAULT_BAND)
    shortfall_ratio = (float(shortfall) / trials_total) if trials_total else 0.0
    band_widened = False
    if shortfall_ratio >= SHORTFALL_WARN:
        if band_given or "low" in band:
            warn("WARNING: %d of %d recent trials (%.0f%%) could not use an untrained word "
                 "(untrained_shortfall); the honest probe is running dry in band %s. %s"
                 % (shortfall, trials_total, 100 * shortfall_ratio, ",".join(band),
                    "Lower --untrained-ratio." if "low" in band
                    else "Widen --band (add low) or lower --untrained-ratio."))
        else:
            band = list(WIDE_BAND)
            band_widened = True
            warn("WARNING: %d of %d recent trials (%.0f%%) could not use an untrained word "
                 "(untrained_shortfall); widening plan.band to %s. Pass --band to keep it narrower."
                 % (shortfall, trials_total, 100 * shortfall_ratio, ",".join(band)))
    if args.feedback not in FEEDBACK:
        raise SystemExit("--feedback must be one of %s" % ", ".join(FEEDBACK))
    voices = [v.strip() for v in args.voices.split(",") if v.strip()] if args.voices else list(cat_voices)
    trials = int(clamp(args.trials, 10, 120))
    ratio = round(clamp(float(args.untrained_ratio), 0.0, 1.0), 3)
    production_pairs = int(clamp(args.production_pairs, 0, 30))
    production_threshold = int(clamp(args.production_threshold, 0, 100))
    max_level = int(clamp(args.max_level, 1, 4))
    weekly_minutes = int(clamp(args.weekly_minutes, 0, 300))
    pins = parse_pins(args.pin, contrasts)

    plan = {
        "version": 1,
        "written": now.strftime("%Y-%m-%dT%H:%M:%SZ"),
        "written_by": "coach",
        "note": args.note or "",
        "trials_per_session": trials,
        "untrained_ratio": ratio,
        "voices": voices,
        "band": band,
        "feedback": args.feedback,
        "production_pairs": production_pairs,
        "production_threshold": production_threshold,
        "max_level": max_level,
        "levels": pins,
        "weekly_minutes_target": weekly_minutes,
        "weights": weights,
    }
    info = {
        "scores": scores, "counts": counts, "misses": misses, "prod_misses": prod_misses,
        "regressed": regressed,
        "sessions_used": n_sessions, "used_defaults": used_defaults,
        "contrasts": contrasts,
        "shortfall": shortfall, "trials": trials_total, "shortfall_ratio": shortfall_ratio,
        "band_widened": band_widened,
    }
    return plan, info


def print_table(plan, info, out=sys.stderr):
    print("%-10s %7s %7s %8s %7s %7s" % ("contrast", "ledger", "misses", "say-miss", "score", "weight"), file=out)
    for cid, _dw, po in info["contrasts"]:
        tag = "  (production only)" if po else ""
        if cid in info["regressed"]:
            tag += "  (regression: -%.0f points, x%.1f)" % (info["regressed"][cid], REGRESSION_FACTOR)
        print("%-10s %7d %7d %8d %7d %7.2f%s" % (
            cid, info["counts"].get(cid, 0), info["misses"].get(cid, 0),
            info["prod_misses"].get(cid, 0), info["scores"].get(cid, 0),
            plan["weights"][cid], tag), file=out)
    if info["used_defaults"]:
        print("no evidence in ledger or recent sessions: catalog default weights", file=out)
    print("sessions in window: %d; trials %d, untrained_ratio %s, band %s%s, feedback %s" % (
        info["sessions_used"], plan["trials_per_session"], plan["untrained_ratio"],
        ",".join(plan["band"]), " (widened: probe shortfall)" if info["band_widened"] else "",
        plan["feedback"]), file=out)
    print("say it: %d pairs, threshold %d; max_level %d, pinned %s; weekly target %d min" % (
        plan["production_pairs"], plan["production_threshold"], plan["max_level"],
        json.dumps(plan["levels"]) if plan["levels"] else "none",
        plan["weekly_minutes_target"]), file=out)
    if info["trials"]:
        print("untrained shortfall in window: %d of %d trials (%.0f%%)" % (
            info["shortfall"], info["trials"], 100 * info["shortfall_ratio"]), file=out)


def write_plan(plan, path):
    d = os.path.dirname(os.path.abspath(path))
    os.makedirs(d, exist_ok=True)
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(plan, f, indent=2, ensure_ascii=False)
        f.write("\n")
    os.replace(tmp, path)


# ----------------------------------------------------------------- selftest

def _session(sid, started, rows, shortfall=0, production=None, levels=None):
    """Synthetic session: rows are (contrast, trained, correct); production is a
    list of (contrast, points) Say-it pairs (None = block skipped)."""
    trials = []
    for i, (contrast, trained, correct) in enumerate(rows, 1):
        trials.append({
            "i": i, "contrast": contrast, "pair": contrast + ":a-b", "target": "a",
            "other": "b", "chosen": "a" if correct else "b", "correct": correct,
            "voice": "en-GB-SoniaNeural", "rt_ms": 900, "replays": 0,
            "trained": trained, "band": "high", "position": "initial",
        })
    n = len(trials)
    c = sum(1 for t in trials if t["correct"])
    prod = None
    if production is not None:
        prod = [{"i": i, "contrast": pc, "pair": pc + ":a-b", "a": "a", "b": "b", "points": pts,
                 "level": (levels or {}).get(pc, 1), "words": {}}
                for i, (pc, pts) in enumerate(production, 1)]
    return {
        "version": 1, "id": sid, "started": started, "ended": started,
        "app_version": "0.1.0", "catalog_version": "test", "plan_source": "coach",
        "plan_written": None, "voices": ["en-GB-SoniaNeural"], "trials": trials,
        "levels": levels or {}, "production": prod,
        "summary": {"trials": n, "correct": c, "pct": c / n if n else 0.0,
                    "untrained_trials": 0, "untrained_correct": 0, "untrained_pct": None,
                    "duration_s": 60, "mean_rt_ms": 900, "untrained_shortfall": shortfall,
                    "contrasts": {}, "production": None},
    }


def selftest():
    now = _dt.datetime(2026, 9, 11, 12, 0, 0, tzinfo=_dt.timezone.utc)
    with tempfile.TemporaryDirectory() as tmp:
        ledger = os.path.join(tmp, "ledger.json")
        sessions = os.path.join(tmp, "sessions")
        os.makedirs(sessions)
        with open(ledger, "w", encoding="utf-8") as f:
            json.dump({"phonemes": {
                "th": {"count": 8, "words": {"think": 5, "three": 3}},
                "s/z": {"count": 4, "words": {}},
                "b/v": {"count": 2},
                "s-cluster": {"count": 9, "words": {"school": 9}},
                "unknown-class": {"count": 50},
                "schwa": {"count": "x"},
            }}, f)
        recent = [
            # inside the window: th 2 untrained misses, i/ii 3, s/z 1 (a trained miss does not count)
            ("20260910T070000Z", "2026-09-10T07:00:00Z",
             [("th", False, False), ("th", False, False), ("th", True, False),
              ("i/ii", False, False), ("i/ii", False, False), ("s/z", False, False)]),
            ("20260905T070000Z", "2026-09-05T07:00:00Z",
             [("i/ii", False, False), ("i/ii", False, True), ("th", False, True)]),
            # outside the 14-day window: must be ignored
            ("20260801T070000Z", "2026-08-01T07:00:00Z",
             [("h", False, False), ("h", False, False), ("h", False, False)]),
        ]
        for sid, started, rows in recent:
            with open(os.path.join(sessions, sid + ".json"), "w", encoding="utf-8") as f:
                json.dump(_session(sid, started, rows), f)
        with open(os.path.join(sessions, "broken.json"), "w") as f:
            f.write("{not json")

        out = os.path.join(tmp, "plan.json")
        args = parse_args(["--ledger", ledger, "--sessions", sessions, "--out", out,
                           "--catalog", default_catalog_path(), "--note", "selftest",
                           "--trials", "30", "--untrained-ratio", "0.4",
                           "--band", "high,mid", "--feedback", "brief",
                           "--voices", "en-GB-SoniaNeural,en-GB-RyanNeural"])
        plan, info = build_plan(args, now)
        write_plan(plan, out)
        with open(out, encoding="utf-8") as f:
            back = json.load(f)      # JSON validity
        assert back == plan
        w = back["weights"]
        # scores: th 8+2*2=12 (max), i/ii 0+2*3=6, s/z 4+2*1=6, b/v 2, h 0 (old session)
        assert info["scores"]["th"] == 12, info["scores"]
        assert info["scores"]["i/ii"] == 6 and info["scores"]["s/z"] == 6
        assert info["scores"]["b/v"] == 2 and info["scores"]["h"] == 0
        assert info["sessions_used"] == 2
        assert w["th"] == 1.0
        assert w["i/ii"] == w["s/z"] == 0.58
        assert w["th"] > w["i/ii"] > w["b/v"] > w["h"], w
        assert w["s-cluster"] == 0.0
        assert "unknown-class" not in w
        for cid, val in w.items():
            if cid != "s-cluster":
                assert FLOOR <= val <= 1.0, (cid, val)
        assert min(v for k, v in w.items() if k != "s-cluster") == FLOOR
        assert not info["used_defaults"]
        assert back["version"] == 1 and back["written_by"] == "coach"
        assert back["written"] == "2026-09-11T12:00:00Z"
        assert back["note"] == "selftest" and back["trials_per_session"] == 30
        assert back["untrained_ratio"] == 0.4 and back["band"] == ["high", "mid"]
        assert info["shortfall"] == 0 and info["trials"] == 9 and not info["band_widened"]
        assert back["feedback"] == "brief"
        assert back["voices"] == ["en-GB-SoniaNeural", "en-GB-RyanNeural"]
        assert back["production_pairs"] == 8 and back["production_threshold"] == 60
        assert back["max_level"] == 4 and back["levels"] == {} and back["weekly_minutes_target"] == 20
        assert info["prod_misses"] == {} and info["regressed"] == {}
        assert sorted(os.listdir(tmp)) == ["ledger.json", "plan.json", "sessions"], os.listdir(tmp)

        # the new levers, --pin repeated, clamping
        args_l = parse_args(["--out", out, "--production-pairs", "12", "--production-threshold", "70",
                             "--max-level", "3", "--pin", "th=2", "--pin", "s/z=1", "--weekly-minutes", "45"])
        plan_l, _ = build_plan(args_l, now)
        assert plan_l["production_pairs"] == 12 and plan_l["production_threshold"] == 70
        assert plan_l["max_level"] == 3 and plan_l["levels"] == {"th": 2, "s/z": 1}
        assert plan_l["weekly_minutes_target"] == 45
        plan_c, _ = build_plan(parse_args(["--out", out, "--production-pairs", "99", "--production-threshold", "-1",
                                           "--max-level", "9", "--weekly-minutes", "1000"]), now)
        assert plan_c["production_pairs"] == 30 and plan_c["production_threshold"] == 0
        assert plan_c["max_level"] == 4 and plan_c["weekly_minutes_target"] == 300
        for bad_pin in ("th", "th=5", "th=x"):
            try:
                build_plan(parse_args(["--out", out, "--pin", bad_pin]), now)
                raise AssertionError("--pin %s accepted" % bad_pin)
            except SystemExit as e:
                assert "--pin" in str(e), e

        # Say-it misses (pairs scored < 2) weigh 3 each, only inside the window
        say = os.path.join(tmp, "say")
        os.makedirs(say)
        say_data = [
            ("20260910T070000Z", "2026-09-10T07:00:00Z", [("th", True, True)],
             [("th", 2), ("th", 1), ("b/v", 0), ("s/z", 2)]),          # th 1 miss, b/v 1 miss
            ("20260909T070000Z", "2026-09-09T07:00:00Z", [("th", False, False)],
             [("b/v", 1)]),                                             # th 1 untrained miss, b/v 1 miss
            ("20260801T070000Z", "2026-08-01T07:00:00Z", [("h", True, True)],
             [("h", 0), ("h", 0)]),                                     # outside the window
        ]
        for sid, started, rows_, prod in say_data:
            with open(os.path.join(say, sid + ".json"), "w", encoding="utf-8") as f:
                json.dump(_session(sid, started, rows_, production=prod), f)
        plan_s, info_s = build_plan(parse_args(["--out", out, "--sessions", say]), now)
        assert info_s["prod_misses"] == {"th": 1, "b/v": 2}, info_s["prod_misses"]
        assert info_s["misses"] == {"th": 1}
        # scores: th 2*1 + 3*1 = 5, b/v 3*2 = 6 (max), s/z 0
        assert info_s["scores"]["th"] == 5 and info_s["scores"]["b/v"] == 6, info_s["scores"]
        assert plan_s["weights"]["b/v"] == 1.0 and plan_s["weights"]["th"] == 0.86, plan_s["weights"]
        assert plan_s["weights"]["s/z"] == FLOOR and plan_s["weights"]["h"] == FLOOR

        # regression: last untrained percent >= 15 points below the mean of the
        # previous two probes -> weight x 1.5 capped at 1.0; sessions without an
        # untrained trial on the contrast do not count as probes
        reg = os.path.join(tmp, "reg")
        os.makedirs(reg)
        probes = {
            # th: 80, 80 then 60 -> drop 20 (regression); s/z: 60, 80, 70 -> drop 0; i/ii: only two probes
            "20260901T070000Z": [("th", False, True)] * 4 + [("th", False, False)] * 1
                                + [("s/z", False, True)] * 3 + [("s/z", False, False)] * 2
                                + [("i/ii", False, True)],
            "20260903T070000Z": [("th", False, True)] * 4 + [("th", False, False)] * 1
                                + [("s/z", False, True)] * 4 + [("s/z", False, False)] * 1
                                + [("i/ii", False, False)],
            "20260905T070000Z": [("th", True, False)] * 3,                       # trained only: not a probe
            "20260907T070000Z": [("th", False, True)] * 3 + [("th", False, False)] * 2
                                + [("s/z", False, True)] * 7 + [("s/z", False, False)] * 3,
        }
        for sid, rows_ in probes.items():
            started = "%s-%s-%sT07:00:00Z" % (sid[:4], sid[4:6], sid[6:8])
            with open(os.path.join(reg, sid + ".json"), "w", encoding="utf-8") as f:
                json.dump(_session(sid, started, rows_), f)
        sess = load_sessions(reg)
        assert [r[1] for r in probe_series(sess)["th"]] == [80.0, 80.0, 60.0], probe_series(sess)["th"]
        assert regression_drop([80.0, 80.0, 60.0]) == 20.0 and regression_drop([80.0, 60.0]) is None
        regs = regressions(sess)
        assert set(regs) == {"th"} and regs["th"] == 20.0, regs
        plan_r, info_r = build_plan(parse_args(["--out", out, "--sessions", reg, "--days", "30"]), now)
        # scores: th 2*(1+1+2)=8, s/z 2*(2+1+3)=12 (max), i/ii 2 -> th 0.15+0.85*8/12=0.72 x1.5=1.0 (cap)
        assert info_r["scores"] == {**{c[0]: 0 for c in info_r["contrasts"]}, "th": 8, "s/z": 12, "i/ii": 2}, info_r["scores"]
        assert plan_r["weights"]["th"] == 1.0 and plan_r["weights"]["s/z"] == 1.0, plan_r["weights"]
        # the same regression with no ledger and the window closed: defaults x 1.5
        plan_r2, info_r2 = build_plan(parse_args(["--out", out, "--sessions", reg, "--days", "0"]), now)
        assert info_r2["used_defaults"] and plan_r2["weights"]["th"] == 1.0
        # a weaker contrast under the rule shows the multiplier itself
        ledger_sz = os.path.join(tmp, "ledger-sz.json")
        with open(ledger_sz, "w", encoding="utf-8") as f:
            json.dump({"phonemes": {"s/z": {"count": 100}}}, f)
        plan_r3, _ = build_plan(parse_args(["--out", out, "--sessions", reg, "--ledger", ledger_sz, "--days", "0"]), now)
        assert plan_r3["weights"]["th"] == round2(FLOOR * REGRESSION_FACTOR) and plan_r3["weights"]["s/z"] == 1.0

        # no evidence at all -> catalog defaults (or the built-in list)
        args2 = parse_args(["--ledger", os.path.join(tmp, "missing.json"),
                            "--sessions", os.path.join(tmp, "nowhere"),
                            "--out", out, "--catalog", default_catalog_path()])
        plan2, info2 = build_plan(args2, now)
        assert info2["used_defaults"]
        assert plan2["weights"]["th"] == 1.0 and plan2["weights"]["s-cluster"] == 0.0
        assert plan2["weights"]["schwa"] == 0.15 and plan2["weights"]["s/z"] == 0.8
        assert plan2["trials_per_session"] == 40 and plan2["untrained_ratio"] == 0.5
        assert plan2["feedback"] == "full" and plan2["note"] == ""
        assert len(plan2["voices"]) == 6

        # ledger with count but no session dir at all -> still works, no defaults
        args3 = parse_args(["--ledger", ledger, "--out", out,
                            "--catalog", os.path.join(tmp, "no-catalog.json")])
        plan3, info3 = build_plan(args3, now)
        assert not info3["used_defaults"]
        assert plan3["weights"]["th"] == 1.0 and plan3["weights"]["s/z"] == 0.58
        assert plan3["weights"]["s-cluster"] == 0.0 and plan3["weights"]["h"] == 0.15
        assert set(plan3["weights"]) == {c[0] for c in FALLBACK_CONTRASTS}

        # clamping
        args4 = parse_args(["--out", out, "--trials", "500", "--untrained-ratio", "3"])
        plan4, _ = build_plan(args4, now)
        assert plan4["trials_per_session"] == 120 and plan4["untrained_ratio"] == 1.0
        assert plan4["band"] == ["high", "mid", "low"]

        # the honest probe ran dry: untrained_shortfall on >= 10 % of the window's
        # trials: the default band is already the whole ceiling, so nothing changes
        # beyond a warning; an explicit narrower --band is kept (with a warning)
        dry = os.path.join(tmp, "dry")
        os.makedirs(dry)
        dry_rows = [("th", True, True)] * 10
        for sid, started, sf in [("20260909T070000Z", "2026-09-09T07:00:00Z", 3),
                                 ("20260910T070000Z", "2026-09-10T07:00:00Z", 0),
                                 ("20260801T070000Z", "2026-08-01T07:00:00Z", 10)]:   # old: ignored
            with open(os.path.join(dry, sid + ".json"), "w", encoding="utf-8") as f:
                json.dump(_session(sid, started, dry_rows, shortfall=sf), f)
        plan5, info5 = build_plan(parse_args(["--out", out, "--sessions", dry]), now)
        assert info5["shortfall"] == 3 and info5["trials"] == 20, info5
        assert not info5["band_widened"] and plan5["band"] == ["high", "mid", "low"], plan5["band"]
        assert info5["shortfall_ratio"] >= SHORTFALL_WARN
        plan6, info6 = build_plan(parse_args(["--out", out, "--sessions", dry, "--band", "high"]), now)
        assert not info6["band_widened"] and plan6["band"] == ["high"], plan6["band"]
        # below the threshold nothing changes
        with open(os.path.join(dry, "20260909T070000Z.json"), "w", encoding="utf-8") as f:
            json.dump(_session("20260909T070000Z", "2026-09-09T07:00:00Z", dry_rows, shortfall=1), f)
        plan7, info7 = build_plan(parse_args(["--out", out, "--sessions", dry, "--band", "high,mid"]), now)
        assert not info7["band_widened"] and plan7["band"] == ["high", "mid"], plan7["band"]
        assert info7["shortfall_ratio"] < SHORTFALL_WARN
    print("plan-from-ledger selftest: OK", file=sys.stderr)


def parse_args(argv):
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    p.add_argument("--ledger")
    p.add_argument("--sessions")
    p.add_argument("--catalog", default=default_catalog_path())
    p.add_argument("--out")
    p.add_argument("--days", type=int, default=14)
    p.add_argument("--note", default="")
    p.add_argument("--trials", type=int, default=40)
    p.add_argument("--untrained-ratio", type=float, default=0.5)
    p.add_argument("--band", default=None, help="default high,mid,low (the ceiling; the level ladder picks within it)")
    p.add_argument("--feedback", default="full", choices=FEEDBACK)
    p.add_argument("--voices")
    p.add_argument("--production-pairs", type=int, default=DEFAULT_PRODUCTION_PAIRS)
    p.add_argument("--production-threshold", type=int, default=DEFAULT_PRODUCTION_THRESHOLD)
    p.add_argument("--max-level", type=int, default=DEFAULT_MAX_LEVEL)
    p.add_argument("--pin", action="append", default=[], metavar="CONTRAST=LEVEL")
    p.add_argument("--weekly-minutes", type=int, default=DEFAULT_WEEKLY_MINUTES)
    p.add_argument("--selftest", action="store_true")
    return p.parse_args(argv)


def main(argv=None):
    args = parse_args(sys.argv[1:] if argv is None else argv)
    if args.selftest:
        selftest()
        return 0
    if not args.out:
        raise SystemExit("--out is required (or use --selftest)")
    if args.days < 0:
        raise SystemExit("--days must be >= 0")
    now = _dt.datetime.now(_dt.timezone.utc).replace(microsecond=0)
    plan, info = build_plan(args, now)
    write_plan(plan, args.out)
    print_table(plan, info)
    print("wrote " + args.out, file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
