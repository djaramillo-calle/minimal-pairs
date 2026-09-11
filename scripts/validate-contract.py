#!/usr/bin/env python3
"""Check a folder laid out like Documents/MinimalPairs against docs/CONTRACT.md.

Usage:
  validate-contract.py <folder> [--catalog data/catalog/catalog.json] [--quiet]
  validate-contract.py --selftest

The folder may hold plan.json, state.json, catalog-version.txt and sessions/.
Each file present is checked (required keys, types, ranges, enumerations, file
name pattern, and that a session's summary agrees with its trial rows). Missing
files are reported as notes, not violations, because a fresh folder legitimately
has none of them. With --catalog, contrast ids, pair ids and target/other words
are also checked against the catalog. Every violation is printed as
"<file>: <message>"; the exit status is 1 when there is at least one, else 0.

Standard library only, Python 3.9+.
"""

import argparse
import datetime as _dt
import json
import os
import re
import sys
import tempfile

CONTRASTS = ["th", "b/v", "i/ii", "j/y", "s/z", "-ed", "schwa", "s-cluster", "h",
             "cat/cut", "long-back", "sh/ch", "er/or"]
BANDS = ("high", "mid", "low")
FEEDBACK = ("full", "brief", "minimal")
PLAN_SOURCES = ("coach", "default", "override")
POSITIONS = ("initial", "medial", "final")
TS_RE = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$")
BASIC_RE = re.compile(r"^\d{8}T\d{6}Z$")
SESSION_FILE_RE = re.compile(r"^\d{8}T\d{6}Z\.json$")
CATALOG_VERSION_RE = re.compile(r"^\d{4}-\d{2}-\d{2}\.\d+$")
PCT_TOL = 0.0015   # percentages are written with 3 decimals
RT_TOL = 1.0       # mean_rt_ms is written as an integer


def is_int(x):
    return isinstance(x, int) and not isinstance(x, bool)


def is_num(x):
    return isinstance(x, (int, float)) and not isinstance(x, bool)


def parse_ts(s):
    """Aware UTC datetime for a contract timestamp, or None when malformed."""
    if not isinstance(s, str) or not TS_RE.match(s):
        return None
    try:
        return _dt.datetime.strptime(s, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=_dt.timezone.utc)
    except ValueError:
        return None


def basic_form(ts):
    return ts.replace("-", "").replace(":", "")


class Report:
    def __init__(self):
        self.violations = []
        self.notes = []

    def bad(self, where, msg):
        self.violations.append("%s: %s" % (where, msg))

    def note(self, msg):
        self.notes.append(msg)


class Checker:
    """Small helpers that record a violation and return whether the check passed."""

    def __init__(self, report, where):
        self.r = report
        self.where = where

    def bad(self, msg):
        self.r.bad(self.where, msg)
        return False

    def key(self, obj, name, pred, desc, required=True):
        """Check obj[name] satisfies pred; returns the value or None."""
        if name not in obj:
            if required:
                self.bad("missing key '%s'" % name)
            return None
        v = obj[name]
        if not pred(v):
            self.bad("'%s' must be %s, got %s" % (name, desc, json.dumps(v)[:60]))
            return None
        return v

    def ts(self, obj, name, nullable=False, required=True):
        if name not in obj:
            if required:
                self.bad("missing key '%s'" % name)
            return None
        v = obj[name]
        if v is None and nullable:
            return None
        if parse_ts(v) is None:
            self.bad("'%s' must be a UTC timestamp like 2026-09-11T07:02:11Z, got %s"
                     % (name, json.dumps(v)[:60]))
            return None
        return v


def load_json_file(path, report):
    try:
        with open(path, encoding="utf-8") as f:
            obj = json.load(f)
    except (OSError, ValueError) as e:
        report.bad(os.path.basename(path), "not valid JSON (%s)" % e)
        return None
    if not isinstance(obj, dict):
        report.bad(os.path.basename(path), "top level must be a JSON object")
        return None
    if obj.get("version") != 1 or not is_int(obj.get("version")):
        report.bad(os.path.basename(path), "'version' must be the integer 1")
    return obj


# ----------------------------------------------------------------- catalog

def load_catalog(path):
    """{contrast id: {pair id: (wordA, wordB)}} or None when no catalog is given."""
    if not path:
        return None
    with open(path, encoding="utf-8") as f:
        cat = json.load(f)
    out = {}
    for c in cat.get("contrasts", []):
        pairs = {}
        for p in c.get("pairs", []):
            pairs[p["id"]] = (p["a"]["word"], p["b"]["word"])
        out[c["id"]] = pairs
    return out


# ----------------------------------------------------------------- plan.json

def check_plan(plan, report, where="plan.json"):
    c = Checker(report, where)
    c.ts(plan, "written", nullable=True, required=False)
    c.key(plan, "written_by", lambda v: isinstance(v, str), "a string", required=False)
    c.key(plan, "note", lambda v: isinstance(v, str), "a string", required=False)
    c.key(plan, "trials_per_session", lambda v: is_int(v) and 10 <= v <= 120,
          "an integer 10-120", required=False)
    c.key(plan, "untrained_ratio", lambda v: is_num(v) and 0 <= v <= 1,
          "a number 0-1", required=False)
    c.key(plan, "voices", lambda v: isinstance(v, list) and all(isinstance(x, str) for x in v),
          "a list of voice ids", required=False)
    c.key(plan, "band", lambda v: isinstance(v, list) and v and all(x in BANDS for x in v),
          "a non-empty list of high/mid/low", required=False)
    c.key(plan, "feedback", lambda v: v in FEEDBACK, "full, brief or minimal", required=False)
    w = c.key(plan, "weights", lambda v: isinstance(v, dict), "an object", required=False)
    if w is not None:
        for cid, val in w.items():
            if not (is_num(val) and 0 <= val <= 1):
                c.bad("weights['%s'] must be a number 0-1, got %s" % (cid, json.dumps(val)[:40]))
            if cid not in CONTRASTS:
                report.note("%s: weights has unknown contrast id '%s' (the app ignores it)" % (where, cid))


# ---------------------------------------------------------------- state.json

def check_counts(c, obj, prefix):
    """trials/correct/untrained_* consistency shared by state and session summaries."""
    t = c.key(obj, "trials", lambda v: is_int(v) and v >= 0, "a non-negative integer")
    k = c.key(obj, "correct", lambda v: is_int(v) and v >= 0, "a non-negative integer")
    ut = c.key(obj, "untrained_trials", lambda v: is_int(v) and v >= 0, "a non-negative integer")
    uk = c.key(obj, "untrained_correct", lambda v: is_int(v) and v >= 0, "a non-negative integer")
    if t is not None and k is not None and k > t:
        c.bad("%scorrect (%d) exceeds trials (%d)" % (prefix, k, t))
    if t is not None and ut is not None and ut > t:
        c.bad("%suntrained_trials (%d) exceeds trials (%d)" % (prefix, ut, t))
    if ut is not None and uk is not None and uk > ut:
        c.bad("%suntrained_correct (%d) exceeds untrained_trials (%d)" % (prefix, uk, ut))
    if k is not None and uk is not None and uk > k:
        c.bad("%suntrained_correct (%d) exceeds correct (%d)" % (prefix, uk, k))
    return t, k, ut, uk


def check_state(state, report, where="state.json"):
    c = Checker(report, where)
    c.ts(state, "updated")
    c.key(state, "app_version", lambda v: isinstance(v, str) and v, "a non-empty string")
    c.key(state, "catalog_version", lambda v: isinstance(v, str) and v, "a non-empty string")
    c.key(state, "sessions_completed", lambda v: is_int(v) and v >= 0, "a non-negative integer")
    c.key(state, "streak_days", lambda v: is_int(v) and v >= 0, "a non-negative integer")
    c.ts(state, "last_session", nullable=True)
    c.key(state, "plan_source", lambda v: v in PLAN_SOURCES, "coach, default or override")
    contrasts = c.key(state, "contrasts", lambda v: isinstance(v, dict), "an object")
    if contrasts is not None:
        for cid, s in contrasts.items():
            cc = Checker(report, "%s contrasts['%s']" % (where, cid))
            if cid not in CONTRASTS:
                cc.bad("unknown contrast id")
            if not isinstance(s, dict):
                cc.bad("must be an object")
                continue
            check_counts(cc, s, "")
            for name in ("last_pct", "last_untrained_pct"):
                cc.key(s, name, lambda v: v is None or (is_num(v) and 0 <= v <= 1), "a number 0-1 or null")
            cc.key(s, "recent_untrained_pct",
                   lambda v: isinstance(v, list) and len(v) <= 5 and all(is_num(x) and 0 <= x <= 1 for x in v),
                   "a list of at most 5 numbers 0-1")
            cc.key(s, "mean_rt_ms", lambda v: v is None or (is_num(v) and v >= 0), "a non-negative number or null")
            wt = cc.key(s, "words_trained", lambda v: is_int(v) and v >= 0, "a non-negative integer")
            wn = cc.key(s, "words_total", lambda v: is_int(v) and v >= 0, "a non-negative integer")
            if wt is not None and wn is not None and wt > wn:
                cc.bad("words_trained (%d) exceeds words_total (%d)" % (wt, wn))
    words = c.key(state, "words", lambda v: isinstance(v, dict), "an object")
    if words is not None:
        for word, w in words.items():
            wc = Checker(report, "%s words['%s']" % (where, word))
            if not isinstance(w, dict):
                wc.bad("must be an object")
                continue
            e = wc.key(w, "exposures", lambda v: is_int(v) and v >= 0, "a non-negative integer")
            k = wc.key(w, "correct", lambda v: is_int(v) and v >= 0, "a non-negative integer")
            if e is not None and k is not None and k > e:
                wc.bad("correct (%d) exceeds exposures (%d)" % (k, e))
            wc.ts(w, "last")


# ------------------------------------------------------------- session files

def check_session(sess, fname, report, catalog=None):
    where = "sessions/" + fname
    c = Checker(report, where)
    base = fname[:-5] if fname.endswith(".json") else fname
    if not SESSION_FILE_RE.match(fname):
        c.bad("file name must look like 20260911T070211Z.json")
    sid = c.key(sess, "id", lambda v: isinstance(v, str) and BASIC_RE.match(v), "a basic-form timestamp id")
    if sid is not None and sid != base:
        c.bad("'id' (%s) differs from the file name (%s)" % (sid, base))
    started = c.ts(sess, "started")
    ended = c.ts(sess, "ended")
    if started is not None and sid is not None and basic_form(started) != sid:
        c.bad("'started' (%s) does not match 'id' (%s)" % (started, sid))
    dur = None
    if started is not None and ended is not None:
        d0, d1 = parse_ts(started), parse_ts(ended)
        if d1 < d0:
            c.bad("'ended' is before 'started'")
        else:
            dur = int((d1 - d0).total_seconds())
    c.key(sess, "app_version", lambda v: isinstance(v, str) and v, "a non-empty string")
    c.key(sess, "catalog_version", lambda v: isinstance(v, str) and v, "a non-empty string")
    c.key(sess, "plan_source", lambda v: v in PLAN_SOURCES, "coach, default or override")
    c.ts(sess, "plan_written", nullable=True)
    voices = c.key(sess, "voices", lambda v: isinstance(v, list) and v and all(isinstance(x, str) for x in v),
                   "a non-empty list of voice ids")
    trials = c.key(sess, "trials", lambda v: isinstance(v, list) and v, "a non-empty list")
    if trials is None:
        return

    # per-trial rows and the tallies the summary must agree with
    n = k = ut = uk = 0
    rt_sum = 0
    per = {}
    for idx, t in enumerate(trials, 1):
        tc = Checker(report, "%s trial %d" % (where, idx))
        if not isinstance(t, dict):
            tc.bad("must be an object")
            continue
        i = tc.key(t, "i", lambda v: is_int(v), "an integer")
        if i is not None and i != idx:
            tc.bad("'i' is %d, expected %d (1-based, in order)" % (i, idx))
        cid = tc.key(t, "contrast", lambda v: isinstance(v, str), "a string")
        if cid is not None and cid not in CONTRASTS:
            tc.bad("unknown contrast id '%s'" % cid)
        pair = tc.key(t, "pair", lambda v: isinstance(v, str), "a string")
        target = tc.key(t, "target", lambda v: isinstance(v, str) and v, "a non-empty string")
        other = tc.key(t, "other", lambda v: isinstance(v, str) and v, "a non-empty string")
        chosen = tc.key(t, "chosen", lambda v: isinstance(v, str) and v, "a non-empty string")
        correct = tc.key(t, "correct", lambda v: isinstance(v, bool), "a boolean")
        if pair is not None and cid is not None and target and other:
            if target == other:
                tc.bad("target and other are the same word")
            prefix = cid + ":"
            if not pair.startswith(prefix):
                tc.bad("pair id '%s' does not start with '%s'" % (pair, prefix))
            else:
                words = pair[len(prefix):].split("-")
                if len(words) != 2 or set(words) != {target, other}:
                    tc.bad("pair id '%s' does not name target '%s' and other '%s'" % (pair, target, other))
                if catalog is not None:
                    if cid not in catalog:
                        tc.bad("contrast '%s' is not in the catalog" % cid)
                    elif pair not in catalog[cid]:
                        tc.bad("pair '%s' is not in the catalog" % pair)
                    elif set(catalog[cid][pair]) != {target, other}:
                        tc.bad("pair '%s' words differ from the catalog" % pair)
        if chosen is not None and target and other and chosen not in (target, other):
            tc.bad("chosen '%s' is neither target nor other" % chosen)
        if correct is not None and chosen is not None and target is not None and correct != (chosen == target):
            tc.bad("'correct' is %s but chosen %s target" % (
                json.dumps(correct), "==" if chosen == target else "!="))
        voice = tc.key(t, "voice", lambda v: isinstance(v, str) and v, "a non-empty string")
        if voice is not None and voices is not None and voice not in voices:
            tc.bad("voice '%s' is not in the session's voices list" % voice)
        rt = tc.key(t, "rt_ms", lambda v: is_int(v) and v >= 0, "a non-negative integer")
        tc.key(t, "replays", lambda v: is_int(v) and v >= 0, "a non-negative integer")
        trained = tc.key(t, "trained", lambda v: isinstance(v, bool), "a boolean")
        tc.key(t, "band", lambda v: v in BANDS, "high, mid or low")
        tc.key(t, "position", lambda v: v in POSITIONS, "initial, medial or final")

        n += 1
        ok = correct is True
        k += 1 if ok else 0
        if trained is False:
            ut += 1
            uk += 1 if ok else 0
        rt_sum += rt if rt is not None else 0
        p = per.setdefault(cid, [0, 0, 0, 0, 0])
        p[0] += 1
        p[1] += 1 if ok else 0
        p[2] += 1 if trained is False else 0
        p[3] += 1 if (trained is False and ok) else 0
        p[4] += rt if rt is not None else 0

    summary = c.key(sess, "summary", lambda v: isinstance(v, dict), "an object")
    if summary is None:
        return
    sc = Checker(report, where + " summary")
    st, sk, sut, suk = check_counts(sc, summary, "")
    if st is not None and st != n:
        sc.bad("trials is %d but there are %d trial rows" % (st, n))
    if sk is not None and sk != k:
        sc.bad("correct is %d but %d rows are correct" % (sk, k))
    if sut is not None and sut != ut:
        sc.bad("untrained_trials is %d but %d rows have trained=false" % (sut, ut))
    if suk is not None and suk != uk:
        sc.bad("untrained_correct is %d but %d untrained rows are correct" % (suk, uk))
    pct = sc.key(summary, "pct", lambda v: is_num(v) and 0 <= v <= 1, "a number 0-1")
    if pct is not None and n and abs(pct - k / n) > PCT_TOL:
        sc.bad("pct is %s but correct/trials is %.3f" % (pct, k / n))
    upct = sc.key(summary, "untrained_pct", lambda v: v is None or (is_num(v) and 0 <= v <= 1),
                  "a number 0-1 (or null when there are no untrained trials)")
    if ut == 0:
        if upct is not None:
            sc.bad("untrained_pct must be null when there are no untrained trials")
    elif upct is None and "untrained_pct" in summary:
        sc.bad("untrained_pct is null but there are %d untrained trials" % ut)
    elif upct is not None and abs(upct - uk / ut) > PCT_TOL:
        sc.bad("untrained_pct is %s but untrained_correct/untrained_trials is %.3f" % (upct, uk / ut))
    ds = sc.key(summary, "duration_s", lambda v: is_int(v) and v >= 0, "a non-negative integer")
    if ds is not None and dur is not None and abs(ds - dur) > 1:
        sc.bad("duration_s is %d but ended - started is %d s" % (ds, dur))
    mrt = sc.key(summary, "mean_rt_ms", lambda v: is_num(v) and v >= 0, "a non-negative number")
    if mrt is not None and n and abs(mrt - rt_sum / n) > RT_TOL:
        sc.bad("mean_rt_ms is %s but the rows average %.1f" % (mrt, rt_sum / n))
    sf = sc.key(summary, "untrained_shortfall", lambda v: is_int(v) and v >= 0, "a non-negative integer")
    if sf is not None and sf > n:
        sc.bad("untrained_shortfall (%d) exceeds trials (%d)" % (sf, n))
    cs = sc.key(summary, "contrasts", lambda v: isinstance(v, dict), "an object")
    if cs is None:
        return
    seen = {cid for cid in per if cid is not None}
    if set(cs) != seen:
        sc.bad("contrasts keys %s differ from the contrasts in the rows %s" % (
            sorted(cs), sorted(seen)))
    for cid, s in cs.items():
        cc = Checker(report, "%s summary.contrasts['%s']" % (where, cid))
        if not isinstance(s, dict):
            cc.bad("must be an object")
            continue
        t, kk, u, ukk = check_counts(cc, s, "")
        mr = cc.key(s, "mean_rt_ms", lambda v: is_num(v) and v >= 0, "a non-negative number")
        if cid not in per:
            continue
        p = per[cid]
        if t is not None and t != p[0]:
            cc.bad("trials is %d but the rows say %d" % (t, p[0]))
        if kk is not None and kk != p[1]:
            cc.bad("correct is %d but the rows say %d" % (kk, p[1]))
        if u is not None and u != p[2]:
            cc.bad("untrained_trials is %d but the rows say %d" % (u, p[2]))
        if ukk is not None and ukk != p[3]:
            cc.bad("untrained_correct is %d but the rows say %d" % (ukk, p[3]))
        if mr is not None and p[0] and abs(mr - p[4] / p[0]) > RT_TOL:
            cc.bad("mean_rt_ms is %s but the rows average %.1f" % (mr, p[4] / p[0]))


# ------------------------------------------------------------------- folder

def validate_folder(folder, catalog=None):
    report = Report()
    if not os.path.isdir(folder):
        report.bad(folder, "not a directory")
        return report

    p = os.path.join(folder, "plan.json")
    if os.path.isfile(p):
        plan = load_json_file(p, report)
        if plan is not None:
            check_plan(plan, report)
    else:
        report.note("no plan.json (the app will use catalog defaults)")

    p = os.path.join(folder, "state.json")
    if os.path.isfile(p):
        state = load_json_file(p, report)
        if state is not None:
            check_state(state, report)
    else:
        report.note("no state.json")

    p = os.path.join(folder, "catalog-version.txt")
    if os.path.isfile(p):
        try:
            with open(p, encoding="utf-8") as f:
                lines = f.read().splitlines()
        except OSError as e:
            lines = None
            report.bad("catalog-version.txt", "unreadable (%s)" % e)
        if lines is not None:
            if len(lines) != 1 or not CATALOG_VERSION_RE.match(lines[0].strip()):
                report.bad("catalog-version.txt", "must be one line like 2026-09-11.1")
    else:
        report.note("no catalog-version.txt")

    sdir = os.path.join(folder, "sessions")
    if os.path.isdir(sdir):
        count = 0
        for fname in sorted(os.listdir(sdir)):
            path = os.path.join(sdir, fname)
            if not os.path.isfile(path):
                continue
            if not fname.endswith(".json"):
                report.bad("sessions/" + fname, "unexpected file in sessions/")
                continue
            count += 1
            sess = load_json_file(path, report)
            if sess is not None:
                check_session(sess, fname, report, catalog)
        report.note("%d session file(s) checked" % count)
    else:
        report.note("no sessions/ folder")
    return report


# ----------------------------------------------------------------- selftest

def _valid_session():
    return {
        "version": 1, "id": "20260901T080000Z", "started": "2026-09-01T08:00:00Z",
        "ended": "2026-09-01T08:01:00Z", "app_version": "0.1.0",
        "catalog_version": "2026-09-11.1", "plan_source": "default", "plan_written": None,
        "voices": ["en-GB-SoniaNeural", "en-GB-RyanNeural"],
        "trials": [
            {"i": 1, "contrast": "th", "pair": "th:through-true", "target": "true", "other": "through",
             "chosen": "true", "correct": True, "voice": "en-GB-SoniaNeural", "rt_ms": 800,
             "replays": 0, "trained": False, "band": "high", "position": "initial"},
            {"i": 2, "contrast": "b/v", "pair": "b/v:berry-very", "target": "berry", "other": "very",
             "chosen": "very", "correct": False, "voice": "en-GB-RyanNeural", "rt_ms": 1200,
             "replays": 2, "trained": True, "band": "mid", "position": "initial"},
            {"i": 3, "contrast": "th", "pair": "th:they-day", "target": "they", "other": "day",
             "chosen": "they", "correct": True, "voice": "en-GB-RyanNeural", "rt_ms": 1000,
             "replays": 0, "trained": False, "band": "high", "position": "initial"},
        ],
        "summary": {
            "trials": 3, "correct": 2, "pct": 0.667,
            "untrained_trials": 2, "untrained_correct": 2, "untrained_pct": 1.0,
            "duration_s": 60, "mean_rt_ms": 1000, "untrained_shortfall": 0,
            "contrasts": {
                "th": {"trials": 2, "correct": 2, "untrained_trials": 2, "untrained_correct": 2, "mean_rt_ms": 900},
                "b/v": {"trials": 1, "correct": 0, "untrained_trials": 0, "untrained_correct": 0, "mean_rt_ms": 1200},
            },
        },
    }


def _valid_state():
    return {
        "version": 1, "updated": "2026-09-01T08:01:01Z", "app_version": "0.1.0",
        "catalog_version": "2026-09-11.1", "sessions_completed": 1, "streak_days": 1,
        "last_session": "2026-09-01T08:00:00Z", "plan_source": "default",
        "contrasts": {"th": {"trials": 2, "correct": 2, "untrained_trials": 2, "untrained_correct": 2,
                             "last_pct": 1.0, "last_untrained_pct": 1.0, "recent_untrained_pct": [1.0],
                             "mean_rt_ms": 900, "words_trained": 2, "words_total": 90}},
        "words": {"true": {"exposures": 1, "correct": 1, "last": "2026-09-01T08:00:10Z"}},
    }


def _valid_plan():
    return {"version": 1, "written": "2026-08-31T18:00:00Z", "written_by": "coach", "note": "",
            "trials_per_session": 40, "untrained_ratio": 0.5, "voices": ["en-GB-SoniaNeural"],
            "band": ["high", "mid"], "feedback": "full", "weights": {"th": 1.0, "s-cluster": 0.0}}


def _write_folder(root, plan, state, sessions, catalog_version="2026-09-11.1\n"):
    os.makedirs(os.path.join(root, "sessions"), exist_ok=True)
    if plan is not None:
        with open(os.path.join(root, "plan.json"), "w", encoding="utf-8") as f:
            json.dump(plan, f)
    if state is not None:
        with open(os.path.join(root, "state.json"), "w", encoding="utf-8") as f:
            json.dump(state, f)
    if catalog_version is not None:
        with open(os.path.join(root, "catalog-version.txt"), "w", encoding="utf-8") as f:
            f.write(catalog_version)
    for fname, s in sessions:
        with open(os.path.join(root, "sessions", fname), "w", encoding="utf-8") as f:
            if isinstance(s, str):
                f.write(s)
            else:
                json.dump(s, f)


def _expect(root, mutate, fragment, catalog=None):
    """Build a folder with one defect applied by mutate(plan, state, session) -> (fname, ...)."""
    plan, state, sess = _valid_plan(), _valid_state(), _valid_session()
    fname = "20260901T080000Z.json"
    res = mutate(plan, state, sess)
    if isinstance(res, str):
        fname = res
    _write_folder(root, plan, state, [(fname, sess)])
    rep = validate_folder(root, catalog)
    hits = [v for v in rep.violations if fragment in v]
    assert hits, "expected a violation containing %r, got %s" % (fragment, rep.violations)
    return rep


def selftest():
    here = os.path.dirname(os.path.abspath(__file__))
    repo = os.path.dirname(here)
    cat_path = os.path.join(repo, "data", "catalog", "catalog.json")
    catalog = load_catalog(cat_path) if os.path.isfile(cat_path) else None

    with tempfile.TemporaryDirectory() as tmp:
        good = os.path.join(tmp, "good")
        _write_folder(good, _valid_plan(), _valid_state(), [("20260901T080000Z.json", _valid_session())])
        rep = validate_folder(good, catalog)
        assert rep.violations == [], rep.violations

        empty = os.path.join(tmp, "empty")
        os.makedirs(empty)
        rep = validate_folder(empty)
        assert rep.violations == [] and len(rep.notes) == 4, (rep.violations, rep.notes)

        cases = [
            (lambda p, s, x: x.__setitem__("version", 2), "'version' must be the integer 1"),
            (lambda p, s, x: "20260901T080000Z.txt", "unexpected file"),
            (lambda p, s, x: "session-1.json", "file name must look like"),
            (lambda p, s, x: "20260901T080001Z.json", "differs from the file name"),
            (lambda p, s, x: x.__setitem__("started", "2026-09-01T08:00:01Z"), "does not match 'id'"),
            (lambda p, s, x: x.__setitem__("ended", "2026-09-01T07:00:00Z"), "'ended' is before"),
            (lambda p, s, x: x.__setitem__("started", "2026-09-01 08:00:00"), "UTC timestamp"),
            (lambda p, s, x: x.__setitem__("plan_source", "robot"), "coach, default or override"),
            (lambda p, s, x: x["trials"][1].__setitem__("i", 3), "'i' is 3, expected 2"),
            (lambda p, s, x: x["trials"][0].__setitem__("contrast", "zz"), "unknown contrast id"),
            (lambda p, s, x: x["trials"][0].__setitem__("pair", "th:think-sink"), "does not name target"),
            (lambda p, s, x: x["trials"][0].__setitem__("chosen", "sheep"), "neither target nor other"),
            (lambda p, s, x: x["trials"][0].__setitem__("correct", False), "'correct' is false but chosen == target"),
            (lambda p, s, x: x["trials"][0].__setitem__("voice", "en-GB-LibbyNeural"), "not in the session's voices"),
            (lambda p, s, x: x["trials"][0].__setitem__("rt_ms", -5), "'rt_ms' must be"),
            (lambda p, s, x: x["trials"][0].__setitem__("band", "top"), "'band' must be"),
            (lambda p, s, x: x["trials"][0].__setitem__("position", "start"), "'position' must be"),
            (lambda p, s, x: x["trials"][0].pop("trained"), "missing key 'trained'"),
            (lambda p, s, x: x["summary"].__setitem__("correct", 3), "correct is 3 but 2 rows"),
            (lambda p, s, x: x["summary"].__setitem__("pct", 0.9), "pct is 0.9 but"),
            (lambda p, s, x: x["summary"].__setitem__("untrained_trials", 1), "untrained_trials is 1 but"),
            (lambda p, s, x: x["summary"].__setitem__("untrained_pct", None), "untrained_pct is null but"),
            (lambda p, s, x: x["summary"].__setitem__("duration_s", 30), "duration_s is 30 but"),
            (lambda p, s, x: x["summary"].__setitem__("mean_rt_ms", 950), "mean_rt_ms is 950 but"),
            (lambda p, s, x: x["summary"]["contrasts"].pop("b/v"), "differ from the contrasts in the rows"),
            (lambda p, s, x: x["summary"]["contrasts"]["th"].__setitem__("mean_rt_ms", 1), "mean_rt_ms is 1 but"),
            (lambda p, s, x: x["summary"]["contrasts"]["th"].__setitem__("untrained_correct", 1), "untrained_correct is 1 but"),
            (lambda p, s, x: p.__setitem__("trials_per_session", 5), "'trials_per_session' must be"),
            (lambda p, s, x: p.__setitem__("feedback", "loud"), "'feedback' must be"),
            (lambda p, s, x: p["weights"].__setitem__("th", 1.5), "weights['th'] must be"),
            (lambda p, s, x: p.__setitem__("band", ["rare"]), "'band' must be"),
            (lambda p, s, x: s["contrasts"]["th"].__setitem__("correct", 9), "correct (9) exceeds trials"),
            (lambda p, s, x: s["contrasts"]["th"].__setitem__("recent_untrained_pct", [1, 1, 1, 1, 1, 1]),
             "'recent_untrained_pct' must be"),
            (lambda p, s, x: s["words"]["true"].__setitem__("correct", 2), "correct (2) exceeds exposures"),
            (lambda p, s, x: s.__setitem__("streak_days", -1), "'streak_days' must be"),
            (lambda p, s, x: s.pop("contrasts"), "missing key 'contrasts'"),
        ]
        for n, (mutate, fragment) in enumerate(cases):
            _expect(os.path.join(tmp, "case%d" % n), mutate, fragment)

        # catalog-aware checks
        if catalog is not None:
            _expect(os.path.join(tmp, "cat1"),
                    lambda p, s, x: (x["trials"][0].update(pair="th:zzz-yyy", target="zzz", other="yyy", chosen="zzz")),
                    "is not in the catalog", catalog)

        # unparsable files and a bad catalog-version line
        bad = os.path.join(tmp, "bad")
        _write_folder(bad, _valid_plan(), _valid_state(),
                      [("20260901T080000Z.json", "{oops")], catalog_version="v1\n")
        rep = validate_folder(bad)
        assert any("not valid JSON" in v for v in rep.violations), rep.violations
        assert any("catalog-version.txt" in v for v in rep.violations), rep.violations
        with open(os.path.join(bad, "state.json"), "w") as f:
            f.write("[]")
        rep = validate_folder(bad)
        assert any("state.json: top level" in v for v in rep.violations), rep.violations

    # the committed examples must pass, with the catalog when it is there
    examples = os.path.join(repo, "data", "examples")
    if os.path.isdir(examples):
        rep = validate_folder(examples, catalog)
        assert rep.violations == [], "data/examples: " + "; ".join(rep.violations)
    print("validate-contract selftest: OK", file=sys.stderr)


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    p.add_argument("folder", nargs="?", help="a folder laid out like Documents/MinimalPairs")
    p.add_argument("--catalog", help="catalog.json to check contrast and pair ids against")
    p.add_argument("--quiet", action="store_true", help="print violations only")
    p.add_argument("--selftest", action="store_true")
    args = p.parse_args(sys.argv[1:] if argv is None else argv)
    if args.selftest:
        selftest()
        return 0
    if not args.folder:
        p.error("folder is required (or use --selftest)")
    catalog = None
    if args.catalog:
        try:
            catalog = load_catalog(args.catalog)
        except (OSError, ValueError, KeyError, TypeError) as e:
            raise SystemExit("cannot read catalog %s: %s" % (args.catalog, e))
    rep = validate_folder(args.folder, catalog)
    for v in rep.violations:
        print("VIOLATION " + v)
    if not args.quiet:
        for n in rep.notes:
            print("note: " + n)
    if rep.violations:
        print("%d violation(s) in %s" % (len(rep.violations), args.folder))
        return 1
    print("OK: %s follows the contract" % args.folder)
    return 0


if __name__ == "__main__":
    sys.exit(main())
