#!/usr/bin/env python3
"""Summarise session files per ISO week and contrast as TSV.

Usage:
  sessions-summary.py <sessions dir> [--weeks N] [--out file.tsv]
  sessions-summary.py --selftest

Reads every *.json in the directory (files that do not parse are skipped with a
warning on stderr), groups trials by the ISO week of "started" (UTC) and by
contrast, and prints one TSV row per (week, contrast), sorted by week then
contrast, with the header

  week  contrast  untrained_pct  untrained_trials  trials  correct_pct  mean_rt_ms
  sessions  week_shortfall  level  days_practised  minutes

untrained_pct is the percent correct on untrained trials (the honest probe),
empty when there were no untrained trials. correct_pct is the percent correct
on all trials. mean_rt_ms is the mean reaction time over all trials of the
group. sessions is the number of session files contributing to the row.
week_shortfall is the sum of summary.untrained_shortfall over every session of
the week (trials that were meant to be untrained but had no untrained word left
in the plan's band); it is session-level, so it repeats on each contrast row of
the week. When it grows, widen plan.band or lower untrained_ratio
(docs/CONTRACT.md). level is the contrast's rung on the level ladder as
last seen that week (the "levels" snapshot of the week's latest session that
carries it; empty for files from before the ladder). days_practised and
minutes are week-level like week_shortfall: distinct UTC days with a session
and the sum of summary.duration_s in minutes (one decimal), repeated on every
contrast row of the week. --weeks N keeps only the N most recent ISO weeks
present in the data.

Standard library only, Python 3.9+. File format: docs/CONTRACT.md.
"""

import argparse
import datetime as _dt
import json
import os
import sys
import tempfile

HEADER = ["week", "contrast", "untrained_pct", "untrained_trials", "trials",
          "correct_pct", "mean_rt_ms", "sessions", "week_shortfall",
          "level", "days_practised", "minutes"]


def is_int(x):
    return isinstance(x, int) and not isinstance(x, bool)


def warn(msg):
    print("sessions-summary: " + msg, file=sys.stderr)


def parse_ts(s):
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


def iso_week(d):
    y, w, _ = d.isocalendar()
    return "%04d-W%02d" % (y, w)


def load_sessions(sessions_dir):
    """Yield (name, session dict) for every parsable *.json in the folder."""
    if not os.path.isdir(sessions_dir):
        raise SystemExit("not a directory: " + sessions_dir)
    for name in sorted(os.listdir(sessions_dir)):
        if not name.endswith(".json"):
            continue
        path = os.path.join(sessions_dir, name)
        try:
            with open(path, encoding="utf-8") as f:
                s = json.load(f)
        except (OSError, ValueError) as e:
            warn("skipping %s: %s" % (name, e))
            continue
        if not isinstance(s, dict) or not isinstance(s.get("trials"), list):
            warn("skipping %s: not a session file" % name)
            continue
        yield name, s


def aggregate(sessions):
    """Return {(week, contrast): stats dict} from (name, session) pairs."""
    groups = {}
    weeks = {}     # week -> {"shortfall", "days": set, "seconds"}

    def group(week, c):
        return groups.setdefault((week, c), {
            "trials": 0, "correct": 0, "untrained_trials": 0,
            "untrained_correct": 0, "rt_sum": 0, "rt_n": 0, "sessions": set(),
            "level": None, "level_at": None,
        })

    for name, s in sessions:
        started = parse_ts(s.get("started"))
        if started is None:
            warn("skipping %s: bad or missing 'started'" % name)
            continue
        week = iso_week(started)
        wk = weeks.setdefault(week, {"shortfall": 0, "days": set(), "seconds": 0})
        wk["days"].add(started.date().isoformat())
        summary = s.get("summary")
        sf = summary.get("untrained_shortfall", 0) if isinstance(summary, dict) else 0
        if is_int(sf) and sf > 0:
            wk["shortfall"] += sf
        dur = summary.get("duration_s") if isinstance(summary, dict) else None
        if is_int(dur) and dur > 0:
            wk["seconds"] += dur
        for t in s["trials"]:
            if not isinstance(t, dict):
                continue
            c = t.get("contrast")
            if not isinstance(c, str):
                continue
            g = group(week, c)
            correct = t.get("correct") is True
            g["trials"] += 1
            g["correct"] += 1 if correct else 0
            if t.get("trained") is False:
                g["untrained_trials"] += 1
                g["untrained_correct"] += 1 if correct else 0
            rt = t.get("rt_ms")
            if isinstance(rt, (int, float)) and not isinstance(rt, bool):
                g["rt_sum"] += rt
                g["rt_n"] += 1
            g["sessions"].add(name)
        levels = s.get("levels")
        if isinstance(levels, dict):
            for c, lv in levels.items():
                if (week, c) in groups and is_int(lv) and 1 <= lv <= 4:
                    g = groups[(week, c)]
                    if g["level_at"] is None or started >= g["level_at"]:
                        g["level"], g["level_at"] = lv, started
    for (week, _c), g in groups.items():
        wk = weeks[week]
        g["week_shortfall"] = wk["shortfall"]
        g["days_practised"] = len(wk["days"])
        g["minutes"] = wk["seconds"] / 60.0
    return groups


def pct(num, den):
    return "" if den == 0 else "%.1f" % (100.0 * num / den)


def rows(groups, weeks=None):
    keys = sorted(groups)
    if weeks is not None and weeks > 0:
        keep = sorted({k[0] for k in keys})[-weeks:]
        keys = [k for k in keys if k[0] in keep]
    out = []
    for week, c in keys:
        g = groups[(week, c)]
        out.append([
            week, c,
            pct(g["untrained_correct"], g["untrained_trials"]),
            str(g["untrained_trials"]),
            str(g["trials"]),
            pct(g["correct"], g["trials"]),
            "" if g["rt_n"] == 0 else str(int(round(g["rt_sum"] / g["rt_n"]))),
            str(len(g["sessions"])),
            str(g.get("week_shortfall", 0)),
            "" if g["level"] is None else str(g["level"]),
            str(g.get("days_practised", 0)),
            "%.1f" % g.get("minutes", 0.0),
        ])
    return out


def write_tsv(table, fh):
    fh.write("\t".join(HEADER) + "\n")
    for r in table:
        fh.write("\t".join(r) + "\n")


# ----------------------------------------------------------------- selftest

def _session(sid, started, rows_, voice="en-GB-SoniaNeural", shortfall=0, duration=60,
             levels=None):
    trials = []
    for i, (contrast, trained, correct, rt) in enumerate(rows_, 1):
        trials.append({
            "i": i, "contrast": contrast, "pair": contrast + ":a-b", "target": "a",
            "other": "b", "chosen": "a" if correct else "b", "correct": correct,
            "voice": voice, "rt_ms": rt, "replays": 0, "trained": trained,
            "band": "high", "position": "initial",
        })
    s = {"version": 1, "id": sid, "started": started, "ended": started,
         "app_version": "0.1.0", "catalog_version": "t", "plan_source": "coach",
         "plan_written": None, "voices": [voice], "trials": trials,
         "summary": {"untrained_shortfall": shortfall, "duration_s": duration}}
    if levels is not None:
        s["levels"] = levels
    return s


def selftest():
    with tempfile.TemporaryDirectory() as tmp:
        data = [
            # 2026-W37 (Mon 7 Sep .. Sun 13 Sep); shortfall 3 + 1 for the week; an old
            # file without levels, then one that carries the snapshot (th level 1)
            ("20260908T070000Z", "2026-09-08T07:00:00Z",
             [("th", False, True, 800), ("th", False, False, 1200), ("th", True, True, 700),
              ("s/z", True, True, 900)], 3, 90, None),
            ("20260913T235959Z", "2026-09-13T23:59:59Z",
             [("th", False, True, 1000), ("s/z", True, False, 1100)], 1, 45,
             {"th": 1, "s/z": 1, "b/v": 1}),
            # 2026-W38 starts on Monday 14 Sep 00:00 UTC: two sessions on one day, the
            # later one shows th at level 2
            ("20260914T000000Z", "2026-09-14T00:00:00Z",
             [("th", False, False, 1500), ("b/v", False, True, 600), ("b/v", True, True, 650)], 0, 120,
             {"th": 1, "b/v": 1}),
            ("20260914T200000Z", "2026-09-14T20:00:00Z",
             [("th", True, True, 900)], 0, 30, {"th": 2}),
        ]
        for sid, started, r, sf, dur, lv in data:
            with open(os.path.join(tmp, sid + ".json"), "w", encoding="utf-8") as f:
                json.dump(_session(sid, started, r, shortfall=sf, duration=dur, levels=lv), f)
        with open(os.path.join(tmp, "broken.json"), "w") as f:
            f.write("not json at all")
        with open(os.path.join(tmp, "notes.txt"), "w") as f:
            f.write("ignored")

        groups = aggregate(load_sessions(tmp))
        table = rows(groups)
        by = {(r[0], r[1]): r for r in table}
        assert [(r[0], r[1]) for r in table] == [
            ("2026-W37", "s/z"), ("2026-W37", "th"), ("2026-W38", "b/v"),
            ("2026-W38", "th")], table
        # W37 th: 4 trials, 3 correct; untrained 3 trials 2 correct; rt (800+1200+700+1000)/4=925;
        # 2 sessions; level 1 (last seen); 2 days, (90+45)/60 = 2.25 -> "2.2"
        assert by[("2026-W37", "th")] == ["2026-W37", "th", "66.7", "3", "4", "75.0", "925", "2", "4",
                                          "1", "2", "2.2"], by
        # W37 s/z: no untrained trials -> empty untrained_pct; week_shortfall is week-level (3 + 1)
        assert by[("2026-W37", "s/z")] == ["2026-W37", "s/z", "", "0", "2", "50.0", "1000", "2", "4",
                                           "1", "2", "2.2"], by
        # W38 th: level 2 from the later session of the day; 3 trials over 2 sessions; one day, 2.5 min
        assert by[("2026-W38", "th")] == ["2026-W38", "th", "0.0", "1", "2", "50.0", "1200", "2", "0",
                                          "2", "1", "2.5"], by
        assert by[("2026-W38", "b/v")] == ["2026-W38", "b/v", "100.0", "1", "2", "100.0", "625", "1", "0",
                                           "1", "1", "2.5"], by
        # every row of a week agrees on the week-level columns
        for week in ("2026-W37", "2026-W38"):
            wk = {tuple(r[8:9] + r[10:12]) for r in table if r[0] == week}
            assert len(wk) == 1, wk

        # --weeks 1 keeps only the most recent week
        last = rows(groups, weeks=1)
        assert {r[0] for r in last} == {"2026-W38"} and len(last) == 2
        # TSV output shape
        out = os.path.join(tmp, "out.tsv")
        with open(out, "w", encoding="utf-8") as f:
            write_tsv(table, f)
        with open(out, encoding="utf-8") as f:
            lines = f.read().splitlines()
        assert lines[0] == "\t".join(HEADER)
        assert len(lines) == 1 + len(table)
        assert all(len(line.split("\t")) == len(HEADER) for line in lines)
    print("sessions-summary selftest: OK", file=sys.stderr)


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    p.add_argument("sessions_dir", nargs="?")
    p.add_argument("--weeks", type=int, default=None, help="keep only the N most recent ISO weeks")
    p.add_argument("--out", help="write the TSV here instead of stdout")
    p.add_argument("--selftest", action="store_true")
    args = p.parse_args(sys.argv[1:] if argv is None else argv)
    if args.selftest:
        selftest()
        return 0
    if not args.sessions_dir:
        p.error("sessions dir is required (or use --selftest)")
    table = rows(aggregate(load_sessions(args.sessions_dir)), args.weeks)
    if args.out:
        with open(args.out, "w", encoding="utf-8") as f:
            write_tsv(table, f)
        print("wrote %s (%d rows)" % (args.out, len(table)), file=sys.stderr)
    else:
        write_tsv(table, sys.stdout)
    return 0


if __name__ == "__main__":
    sys.exit(main())
