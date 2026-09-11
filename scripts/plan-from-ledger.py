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
  --band a,b           plan.band, default high,mid. When the recent sessions report
                       untrained_shortfall on at least 10 % of their trials (the
                       honest probe has run out of untrained words in the band, see
                       docs/CONTRACT.md) the default widens to high,mid,low; an
                       explicit --band is kept and a loud warning is printed instead.
  --feedback LEVEL     full | brief | minimal (default full)
  --voices a,b         plan.voices (default: every voice of the catalog)
  --selftest           run the built-in checks on synthetic data and exit

Algorithm (docs/CONTRACT.md, "Coach side"):
  score(c)  = ledger_count(c) + 2 * recent_misses(c)
  weight(c) = 0.15 + 0.85 * score(c) / max_score          rounded to 2 decimals
  recent_misses(c): incorrect untrained trials on c in sessions started within
  the last --days days. Production-only contrasts (s-cluster) get weight 0.
  With no evidence at all (max_score == 0) the catalog default weights are used.
  Ledger class names equal contrast ids.

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
# Share of the window's trials that were meant to be untrained but had no
# untrained word left (summary.untrained_shortfall) above which the probe is
# considered collapsed: widen the band (default) or warn (explicit --band).
SHORTFALL_WARN = 0.10
DEFAULT_BAND = ["high", "mid"]
WIDE_BAND = ["high", "mid", "low"]

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


def recent_misses(sessions_dir, days, now):
    """({contrast: incorrect untrained trials}, sessions used, shortfall, trials)
    over sessions started within the window. shortfall is the sum of
    summary.untrained_shortfall, trials the number of trial rows."""
    misses = {}
    if not sessions_dir or not os.path.isdir(sessions_dir):
        return misses, 0, 0, 0
    cutoff = now - _dt.timedelta(days=days)
    used = 0
    shortfall = 0
    trials_total = 0
    for name in sorted(os.listdir(sessions_dir)):
        if not name.endswith(".json"):
            continue
        s = load_json(os.path.join(sessions_dir, name))
        if not isinstance(s, dict):
            continue
        started = parse_ts(s.get("started"))
        if started is None or started < cutoff:
            continue
        trials = s.get("trials")
        if not isinstance(trials, list):
            continue
        used += 1
        summary = s.get("summary")
        if isinstance(summary, dict):
            sf = summary.get("untrained_shortfall", 0)
            if isinstance(sf, int) and not isinstance(sf, bool) and sf > 0:
                shortfall += sf
        for t in trials:
            if not isinstance(t, dict):
                continue
            trials_total += 1
            if t.get("trained") is False and t.get("correct") is False:
                c = t.get("contrast")
                if isinstance(c, str):
                    misses[c] = misses.get(c, 0) + 1
    return misses, used, shortfall, trials_total


def clamp(x, lo, hi):
    return lo if x < lo else hi if x > hi else x


def round2(x):
    """Round half up to 2 decimals (0.575 -> 0.58), immune to float noise."""
    return round(x + 1e-9, 2)


def compute_weights(contrasts, counts, misses):
    """Return ({id: weight}, {id: score}, used_defaults)."""
    scores = {}
    for cid, _dw, po in contrasts:
        if po:
            scores[cid] = 0
        else:
            scores[cid] = counts.get(cid, 0) + 2 * misses.get(cid, 0)
    max_score = max(scores.values()) if scores else 0
    weights = {}
    if max_score <= 0:
        for cid, dw, po in contrasts:
            weights[cid] = 0.0 if po else round2(dw)
        return weights, scores, True
    for cid, _dw, po in contrasts:
        if po:
            weights[cid] = 0.0
        else:
            weights[cid] = round2(FLOOR + SPAN * scores[cid] / max_score)
    return weights, scores, False


def build_plan(args, now):
    contrasts, cat_voices = load_contrasts(args.catalog)
    counts = ledger_counts(load_json(args.ledger))
    misses, n_sessions, shortfall, trials_total = recent_misses(args.sessions, args.days, now)
    weights, scores, used_defaults = compute_weights(contrasts, counts, misses)

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
                 "(untrained_shortfall); the honest probe is running dry in band %s. "
                 "Widen --band (add low) or lower --untrained-ratio."
                 % (shortfall, trials_total, 100 * shortfall_ratio, ",".join(band)))
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
        "weights": weights,
    }
    info = {
        "scores": scores, "counts": counts, "misses": misses,
        "sessions_used": n_sessions, "used_defaults": used_defaults,
        "contrasts": contrasts,
        "shortfall": shortfall, "trials": trials_total, "shortfall_ratio": shortfall_ratio,
        "band_widened": band_widened,
    }
    return plan, info


def print_table(plan, info, out=sys.stderr):
    print("%-10s %7s %7s %7s %7s" % ("contrast", "ledger", "misses", "score", "weight"), file=out)
    for cid, _dw, po in info["contrasts"]:
        print("%-10s %7d %7d %7d %7.2f%s" % (
            cid, info["counts"].get(cid, 0), info["misses"].get(cid, 0),
            info["scores"].get(cid, 0), plan["weights"][cid],
            "  (production only)" if po else ""), file=out)
    if info["used_defaults"]:
        print("no evidence in ledger or recent sessions: catalog default weights", file=out)
    print("sessions in window: %d; trials %d, untrained_ratio %s, band %s%s, feedback %s" % (
        info["sessions_used"], plan["trials_per_session"], plan["untrained_ratio"],
        ",".join(plan["band"]), " (widened: probe shortfall)" if info["band_widened"] else "",
        plan["feedback"]), file=out)
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

def _session(sid, started, rows, shortfall=0):
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
    return {
        "version": 1, "id": sid, "started": started, "ended": started,
        "app_version": "0.1.0", "catalog_version": "test", "plan_source": "coach",
        "plan_written": None, "voices": ["en-GB-SoniaNeural"], "trials": trials,
        "summary": {"trials": n, "correct": c, "pct": c / n if n else 0.0,
                    "untrained_trials": 0, "untrained_correct": 0, "untrained_pct": None,
                    "duration_s": 60, "mean_rt_ms": 900, "untrained_shortfall": shortfall,
                    "contrasts": {}},
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
        assert sorted(os.listdir(tmp)) == ["ledger.json", "plan.json", "sessions"]

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
        assert plan4["band"] == ["high", "mid"]

        # the honest probe ran dry: untrained_shortfall on >= 10 % of the window's
        # trials widens the default band; an explicit --band is kept (with a warning)
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
        assert info5["band_widened"] and plan5["band"] == ["high", "mid", "low"], plan5["band"]
        plan6, info6 = build_plan(parse_args(["--out", out, "--sessions", dry, "--band", "high"]), now)
        assert not info6["band_widened"] and plan6["band"] == ["high"], plan6["band"]
        # below the threshold nothing changes
        with open(os.path.join(dry, "20260909T070000Z.json"), "w", encoding="utf-8") as f:
            json.dump(_session("20260909T070000Z", "2026-09-09T07:00:00Z", dry_rows, shortfall=1), f)
        plan7, info7 = build_plan(parse_args(["--out", out, "--sessions", dry]), now)
        assert not info7["band_widened"] and plan7["band"] == ["high", "mid"], plan7["band"]
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
    p.add_argument("--band", default=None, help="default high,mid (high,mid,low when the probe ran dry)")

    p.add_argument("--feedback", default="full", choices=FEEDBACK)
    p.add_argument("--voices")
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
