#!/usr/bin/env python3
"""Check a folder laid out like Documents/MinimalPairs against docs/CONTRACT.md.

Usage:
  validate-contract.py <folder> [--catalog data/catalog/catalog.json] [--quiet]
  validate-contract.py --selftest

The folder may hold plan.json, state.json, catalog-version.txt and sessions/.
Each file present is checked (required keys, types, ranges, enumerations, file
name pattern, and that a session's summary agrees with its trial rows and its
Say-it production rows). Missing files are reported as notes, not violations,
because a fresh folder legitimately has none of them. The level-ladder and
Say-it keys (plan levers, state.contrasts.<id>.level and production totals,
state.pairs, state.practice, session levels and production rows) are additive:
files from app versions before them still pass, but a session that carries
"levels" must also carry "production" (a list, or null when the block was off
or skipped), and a state contrast that carries any of the new keys must carry
them all, as the app writes every key of its schema. With --catalog, contrast ids, pair ids and target/other words
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
DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
VOTE = ("intended", "other", "none")
VOTES_RE = re.compile(r"^phoneme:(intended|other|none) word:(intended|other|none) recognition:(intended|other|none)$")
# contrasts whose phoneme signal is skipped (docs/CONTRACT.md, "Say it")
NO_PHONEME_SIGNAL = ("long-back", "schwa")
# insertion contrasts: the differing phoneme exists in one word only, so it is scored
# under the reference that contains it and votes on that one score (docs/CONTRACT.md, "Say it")
ONE_SIDED_PHONEME = ("-ed", "h")
ONE_SIDED_PRESENT = 60   # the carrying word wins from here up
ONE_SIDED_ABSENT = 40    # the other word wins below here; in between nothing votes
PHONEME_MARGIN = 15   # ph must beat ph_other by this much to vote
WORD_MARGIN = 10      # acc must beat acc_other by this much to vote
STATE_PRODUCTION_KEYS = ("level", "level_changed", "production_pairs", "production_points",
                         "last_production_pct", "recent_production_pct")
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


def is_date(s):
    if not isinstance(s, str) or not DATE_RE.match(s):
        return False
    try:
        _dt.date.fromisoformat(s)
    except ValueError:
        return False
    return True


def is_level(v):
    return is_int(v) and 1 <= v <= 4


def is_pct(v):
    return is_num(v) and 0 <= v <= 1


def is_score(v):
    return is_num(v) and 0 <= v <= 100


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
    c.key(plan, "production_pairs", lambda v: is_int(v) and 0 <= v <= 30, "an integer 0-30", required=False)
    c.key(plan, "production_threshold", lambda v: is_int(v) and 0 <= v <= 100, "an integer 0-100",
          required=False)
    c.key(plan, "max_level", is_level, "an integer 1-4", required=False)
    lv = c.key(plan, "levels", lambda v: isinstance(v, dict), "an object", required=False)
    if lv is not None:
        for cid, val in lv.items():
            if not is_level(val):
                c.bad("levels['%s'] must be an integer 1-4, got %s" % (cid, json.dumps(val)[:40]))
            if cid not in CONTRASTS:
                report.note("%s: levels has unknown contrast id '%s' (the app ignores it)" % (where, cid))
    c.key(plan, "weekly_minutes_target", lambda v: is_int(v) and 0 <= v <= 300, "an integer 0-300",
          required=False)


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


def check_state(state, report, where="state.json", catalog=None):
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
            if any(k in s for k in STATE_PRODUCTION_KEYS):
                check_state_contrast_production(cc, s)
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
    pairs = c.key(state, "pairs", lambda v: isinstance(v, dict), "an object", required=False)
    if pairs is not None:
        for pid, pr in pairs.items():
            pc = Checker(report, "%s pairs['%s']" % (where, pid))
            check_pair_id(pc, pid, catalog)
            if not isinstance(pr, dict):
                pc.bad("must be an object")
                continue
            at = pc.key(pr, "attempts", lambda v: is_int(v) and v >= 1, "an integer >= 1")
            lp = pc.key(pr, "last_points", lambda v: is_int(v) and 0 <= v <= 2, "an integer 0-2")
            best = pc.key(pr, "best", lambda v: is_int(v) and 0 <= v <= 2, "an integer 0-2")
            fails = pc.key(pr, "fails", lambda v: is_int(v) and v >= 0, "a non-negative integer")
            if lp is not None and best is not None and lp > best:
                pc.bad("last_points (%d) exceeds best (%d)" % (lp, best))
            if at is not None and fails is not None and fails > at:
                pc.bad("fails (%d) exceeds attempts (%d)" % (fails, at))
            if lp is not None and fails is not None and lp < 2 and fails == 0:
                pc.bad("last_points is %d (a fail) but fails is 0" % lp)
            pc.ts(pr, "last")
    practice = c.key(state, "practice", lambda v: isinstance(v, dict), "an object", required=False)
    if practice is not None:
        pc = Checker(report, where + " practice")
        ls = pc.key(practice, "longest_streak", lambda v: is_int(v) and v >= 0, "a non-negative integer")
        streak = state.get("streak_days")
        if ls is not None and is_int(streak) and streak > ls:
            pc.bad("longest_streak (%d) is below streak_days (%d)" % (ls, streak))
        total = pc.key(practice, "total_seconds", lambda v: is_int(v) and v >= 0, "a non-negative integer")
        days = pc.key(practice, "days", lambda v: isinstance(v, dict), "an object")
        if days is not None:
            seconds = 0
            for day, d in days.items():
                dc = Checker(report, "%s practice.days['%s']" % (where, day))
                if not is_date(day):
                    dc.bad("key must be a UTC date like 2026-09-11")
                if not isinstance(d, dict):
                    dc.bad("must be an object")
                    continue
                dc.key(d, "sessions", lambda v: is_int(v) and v >= 1, "an integer >= 1")
                sec = dc.key(d, "seconds", lambda v: is_int(v) and v >= 0, "a non-negative integer")
                dc.key(d, "perception_trials", lambda v: is_int(v) and v >= 0, "a non-negative integer")
                dc.key(d, "production_pairs", lambda v: is_int(v) and v >= 0, "a non-negative integer")
                seconds += sec or 0
            if total is not None and seconds > total:
                pc.bad("days sum to %d seconds but total_seconds is %d" % (seconds, total))


def check_state_contrast_production(cc, s):
    """The level-ladder and Say-it keys of a state contrast (all present or none)."""
    for k in STATE_PRODUCTION_KEYS:
        if k not in s:
            cc.bad("missing key '%s' (present with the other Say-it keys)" % k)
    cc.key(s, "level", is_level, "an integer 1-4", required=False)
    cc.ts(s, "level_changed", nullable=True, required=False)
    pp = cc.key(s, "production_pairs", lambda v: is_int(v) and v >= 0, "a non-negative integer",
                required=False)
    pts = cc.key(s, "production_points", lambda v: is_int(v) and v >= 0, "a non-negative integer",
                 required=False)
    if pp is not None and pts is not None and pts > 2 * pp:
        cc.bad("production_points (%d) exceeds 2 x production_pairs (%d)" % (pts, pp))
    lpp = cc.key(s, "last_production_pct", lambda v: v is None or is_pct(v), "a number 0-1 or null",
                 required=False)
    rp = cc.key(s, "recent_production_pct",
                lambda v: isinstance(v, list) and len(v) <= 5 and all(is_pct(x) for x in v),
                "a list of at most 5 numbers 0-1", required=False)
    if pp == 0:
        if lpp is not None:
            cc.bad("last_production_pct must be null when production_pairs is 0")
        if rp:
            cc.bad("recent_production_pct must be empty when production_pairs is 0")
    elif pp is not None and pp > 0:
        if "last_production_pct" in s and lpp is None:
            cc.bad("last_production_pct is null but production_pairs is %d" % pp)
        if rp is not None and not rp:
            cc.bad("recent_production_pct is empty but production_pairs is %d" % pp)
        if rp and lpp is not None and abs(rp[-1] - lpp) > PCT_TOL:
            cc.bad("last_production_pct (%s) differs from the last recent_production_pct (%s)" % (lpp, rp[-1]))


def check_pair_id(c, pid, catalog):
    """A pair id looks like <contrast>:<wordA>-<wordB>; against the catalog when given.
    Returns (contrast, a, b) or None."""
    if not isinstance(pid, str) or ":" not in pid:
        c.bad("pair id '%s' must look like <contrast>:<wordA>-<wordB>" % pid)
        return None
    cid, rest = pid.split(":", 1)
    words = rest.split("-")
    if cid not in CONTRASTS:
        c.bad("pair id '%s' has an unknown contrast id" % pid)
        return None
    if len(words) != 2 or not all(words):
        c.bad("pair id '%s' must name two words" % pid)
        return None
    if catalog is not None:
        if cid not in catalog:
            c.bad("contrast '%s' is not in the catalog" % cid)
        elif pid not in catalog[cid]:
            c.bad("pair '%s' is not in the catalog" % pid)
    return cid, words[0], words[1]


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

    # level ladder snapshot and the Say-it block (additive: absent in old files)
    levels = None
    if "levels" in sess:
        levels = c.key(sess, "levels", lambda v: isinstance(v, dict), "an object")
        if levels is not None:
            for cid, lv in levels.items():
                if cid not in CONTRASTS:
                    c.bad("levels has unknown contrast id '%s'" % cid)
                if not is_level(lv):
                    c.bad("levels['%s'] must be an integer 1-4, got %s" % (cid, json.dumps(lv)[:40]))
            for cid in per:
                if cid is not None and cid not in levels:
                    c.bad("contrast '%s' has trials but no entry in levels" % cid)
        if "production" not in sess:
            c.bad("missing key 'production' (a session with 'levels' carries it, null when the block was off or skipped)")
    prod = None
    if "production" in sess:
        prod = c.key(sess, "production", lambda v: v is None or isinstance(v, list),
                     "a list of pair rows or null")
    prod_tally = check_production_rows(prod, where, report, catalog, levels) if prod else None

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
    pds = sc.key(summary, "perception_duration_s", lambda v: is_int(v) and v >= 0,
                 "a non-negative integer", required=False)
    if pds is not None and ds is not None and pds > ds:
        sc.bad("perception_duration_s (%d) exceeds duration_s (%d)" % (pds, ds))
    if "production" in sess:
        if "production" not in summary:
            sc.bad("missing key 'production' (null when the session's production is null)")
        else:
            check_production_summary(sc, summary["production"], prod, prod_tally, ds, pds, where, report)
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


def _one_sided_vote(ph, ph_other):
    """intended / other / none when only one reference carries the differing phoneme."""
    score = ph if ph is not None else ph_other
    if score is None:
        return "none"
    carrier, absent = ("intended", "other") if ph is not None else ("other", "intended")
    if score >= ONE_SIDED_PRESENT:
        return carrier
    if score < ONE_SIDED_ABSENT:
        return absent
    return "none"


def _vote_from_scores(mine, theirs, margin):
    """intended / other / none by the contract's margin rule; None when unknown."""
    if mine is None or theirs is None:
        return "none"
    if mine >= theirs + margin:
        return "intended"
    if theirs >= mine + margin:
        return "other"
    return "none"


def check_production_rows(prod, where, report, catalog, levels):
    """Check the Say-it rows; returns (pairs, points, {contrast: [pairs, points]})."""
    tally = {}
    n_pairs = 0
    points_sum = 0
    seen_pairs = set()
    for idx, row in enumerate(prod, 1):
        rc = Checker(report, "%s production %d" % (where, idx))
        if not isinstance(row, dict):
            rc.bad("must be an object")
            continue
        i = rc.key(row, "i", lambda v: is_int(v), "an integer")
        if i is not None and i != idx:
            rc.bad("'i' is %d, expected %d (1-based, in order)" % (i, idx))
        cid = rc.key(row, "contrast", lambda v: isinstance(v, str), "a string")
        if cid is not None and cid not in CONTRASTS:
            rc.bad("unknown contrast id '%s'" % cid)
        pair = rc.key(row, "pair", lambda v: isinstance(v, str), "a string")
        a = rc.key(row, "a", lambda v: isinstance(v, str) and v, "a non-empty string")
        b = rc.key(row, "b", lambda v: isinstance(v, str) and v, "a non-empty string")
        points = rc.key(row, "points", lambda v: is_int(v) and 0 <= v <= 2, "an integer 0-2")
        level = rc.key(row, "level", is_level, "an integer 1-4")
        if level is not None and levels is not None and cid in levels and is_level(levels[cid]) \
                and levels[cid] != level:
            rc.bad("level is %d but the session's levels['%s'] is %d" % (level, cid, levels[cid]))
        if pair is not None and cid is not None and a and b:
            if a == b:
                rc.bad("a and b are the same word")
            expect = "%s:%s-%s" % (cid, a, b)
            if pair != expect:
                rc.bad("pair id '%s' should be '%s' (contrast, then a-b in catalog order)" % (pair, expect))
            elif catalog is not None:
                if cid not in catalog:
                    rc.bad("contrast '%s' is not in the catalog" % cid)
                elif pair not in catalog[cid]:
                    rc.bad("pair '%s' is not in the catalog" % pair)
                elif catalog[cid][pair] != (a, b):
                    rc.bad("pair '%s' words differ from the catalog" % pair)
            if pair in seen_pairs:
                rc.bad("pair '%s' appears twice in the block" % pair)
            seen_pairs.add(pair)
        words = rc.key(row, "words", lambda v: isinstance(v, dict), "an object")
        earned_max = 0     # words that could have earned a point (heard == intended)
        earned_min = 0     # words that must have (heard == intended and acc == 100)
        if words is not None and a and b:
            if set(words) != {a, b}:
                rc.bad("words keys %s must be exactly a and b (%s, %s)" % (sorted(words), a, b))
            for w, wr in words.items():
                wc = Checker(report, "%s production %d word '%s'" % (where, idx, w))
                if not isinstance(wr, dict):
                    wc.bad("must be an object")
                    continue
                other = b if w == a else a
                heard = wc.key(wr, "heard", lambda v: v in (a, b, "?"), "the intended word, the other word or '?'")
                acc = wc.key(wr, "acc", is_score, "a number 0-100")
                acc_other = wc.key(wr, "acc_other", is_score, "a number 0-100")
                ph = wc.key(wr, "ph", lambda v: v is None or is_score(v), "a number 0-100 or null")
                ph_other = wc.key(wr, "ph_other", lambda v: v is None or is_score(v), "a number 0-100 or null")
                votes = wc.key(wr, "votes", lambda v: isinstance(v, str) and VOTES_RE.match(v),
                               "like 'phoneme:intended word:other recognition:none'")
                rec = wc.key(wr, "recognised", lambda v: v is None or isinstance(v, str),
                             "a string (or null when nothing was recognised)")
                if rec is not None and (rec != rec.lower() or rec != rec.strip()
                                        or any(ch in rec for ch in ".,!?;:\"")):
                    wc.bad("recognised '%s' must be lower-cased with punctuation stripped" % rec)
                wc.key(wr, "ms", lambda v: is_int(v) and v >= 0, "a non-negative integer")
                wc.key(wr, "attempts", lambda v: is_int(v) and 1 <= v <= 2, "an integer 1-2")
                if votes is None:
                    continue
                v_ph, v_word, v_rec = VOTES_RE.match(votes).groups()
                if cid in NO_PHONEME_SIGNAL:
                    if v_ph != "none":
                        wc.bad("phoneme vote must be none for contrast '%s'" % cid)
                    if ph is not None or ph_other is not None:
                        wc.bad("ph and ph_other must be null for contrast '%s'" % cid)
                elif cid in ONE_SIDED_PHONEME and "ph" in wr and "ph_other" in wr:
                    if ph is not None and ph_other is not None:
                        wc.bad("only one reference of an insertion pair carries the differing phoneme, "
                               "so ph and ph_other cannot both be scores (%s, %s)" % (ph, ph_other))
                    else:
                        want = _one_sided_vote(ph, ph_other)
                        if v_ph != want:
                            wc.bad("phoneme vote is %s but ph %s / ph_other %s gives %s"
                                   % (v_ph, ph, ph_other, want))
                elif "ph" in wr and "ph_other" in wr:
                    want = _vote_from_scores(ph, ph_other, PHONEME_MARGIN)
                    if v_ph != want:
                        wc.bad("phoneme vote is %s but ph %s vs ph_other %s gives %s" % (v_ph, ph, ph_other, want))
                if acc is not None and acc_other is not None:
                    want = _vote_from_scores(acc, acc_other, WORD_MARGIN)
                    if v_word != want:
                        wc.bad("word vote is %s but acc %s vs acc_other %s gives %s" % (v_word, acc, acc_other, want))
                if "recognised" in wr and rec is None and v_rec != "none":
                    wc.bad("recognition vote is %s but nothing was recognised" % v_rec)
                if rec is not None:
                    if rec == w and v_rec != "intended":
                        wc.bad("recognition vote is %s but recognised is the intended word" % v_rec)
                    if rec == other and v_rec != "other":
                        wc.bad("recognition vote is %s but recognised is the other word" % v_rec)
                cast = [v_ph, v_word, v_rec]
                n_int = cast.count("intended")
                n_oth = cast.count("other")
                want = w if n_int > n_oth else other if n_oth > n_int else "?"
                if heard is not None and heard != want:
                    wc.bad("heard is '%s' but the votes (%s) give '%s'" % (heard, votes, want))
                if heard == w:
                    earned_max += 1
                    if acc is not None and acc >= 100:
                        earned_min += 1
        if points is not None and words is not None and a and b and set(words) == {a, b}:
            if points > earned_max:
                rc.bad("points is %d but only %d word(s) were heard as intended" % (points, earned_max))
            if points < earned_min:
                rc.bad("points is %d but %d word(s) heard as intended at accuracy 100 must score" % (points, earned_min))
        n_pairs += 1
        points_sum += points or 0
        t = tally.setdefault(cid, [0, 0])
        t[0] += 1
        t[1] += points or 0
    return n_pairs, points_sum, tally


def check_production_summary(sc, ps, prod, prod_tally, ds, pds, where, report):
    """summary.production against the production rows."""
    if prod is None:
        if ps is not None:
            sc.bad("production must be null when the session's production is null")
        return
    if not isinstance(ps, dict):
        sc.bad("production must be an object when the session has production rows, got %s" % json.dumps(ps)[:40])
        return
    pc = Checker(report, where + " summary.production")
    n_pairs, points_sum, tally = prod_tally if prod_tally else (0, 0, {})
    pairs = pc.key(ps, "pairs", lambda v: is_int(v) and v >= 0, "a non-negative integer")
    pts = pc.key(ps, "points", lambda v: is_int(v) and v >= 0, "a non-negative integer")
    mx = pc.key(ps, "max_points", lambda v: is_int(v) and v >= 0, "a non-negative integer")
    if pairs is not None and pairs != n_pairs:
        pc.bad("pairs is %d but there are %d production rows" % (pairs, n_pairs))
    if pts is not None and pts != points_sum:
        pc.bad("points is %d but the rows sum to %d" % (pts, points_sum))
    if mx is not None and mx != 2 * n_pairs:
        pc.bad("max_points is %d but 2 x %d rows is %d" % (mx, n_pairs, 2 * n_pairs))
    pct = pc.key(ps, "pct", lambda v: v is None or is_pct(v), "a number 0-1 (or null when there are no pairs)")
    if n_pairs == 0:
        if pct is not None:
            pc.bad("pct must be null when there are no production rows")
    elif "pct" in ps and pct is None:
        pc.bad("pct is null but there are %d production rows" % n_pairs)
    elif pct is not None and abs(pct - points_sum / (2.0 * n_pairs)) > PCT_TOL:
        pc.bad("pct is %s but points/max_points is %.3f" % (pct, points_sum / (2.0 * n_pairs)))
    pd = pc.key(ps, "duration_s", lambda v: is_int(v) and v >= 0, "a non-negative integer")
    if pd is not None and ds is not None and pd > ds:
        pc.bad("duration_s (%d) exceeds the session's duration_s (%d)" % (pd, ds))
    if pd is not None and pds is not None and ds is not None and pd + pds > ds:
        pc.bad("duration_s (%d) plus perception_duration_s (%d) exceeds the session's duration_s (%d)"
               % (pd, pds, ds))
    cs = pc.key(ps, "contrasts", lambda v: isinstance(v, dict), "an object")
    if cs is None:
        return
    seen = {cid for cid in tally if cid is not None}
    if set(cs) != seen:
        pc.bad("contrasts keys %s differ from the contrasts in the production rows %s" % (sorted(cs), sorted(seen)))
    for cid, sub in cs.items():
        cc = Checker(report, "%s summary.production.contrasts['%s']" % (where, cid))
        if not isinstance(sub, dict):
            cc.bad("must be an object")
            continue
        p_ = cc.key(sub, "pairs", lambda v: is_int(v) and v >= 0, "a non-negative integer")
        k_ = cc.key(sub, "points", lambda v: is_int(v) and v >= 0, "a non-negative integer")
        if cid not in tally:
            continue
        if p_ is not None and p_ != tally[cid][0]:
            cc.bad("pairs is %d but the rows say %d" % (p_, tally[cid][0]))
        if k_ is not None and k_ != tally[cid][1]:
            cc.bad("points is %d but the rows say %d" % (k_, tally[cid][1]))


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
            check_state(state, report, catalog=catalog)
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
        "levels": {"th": 2, "b/v": 1},
        "production": [
            {"i": 1, "contrast": "th", "pair": "th:they-day", "a": "they", "b": "day", "points": 1, "level": 2,
             "words": {
                 "they": {"heard": "day", "acc": 72, "acc_other": 90, "ph": 35, "ph_other": 96,
                          "votes": "phoneme:other word:other recognition:other", "recognised": "day",
                          "ms": 640, "attempts": 1},
                 "day": {"heard": "day", "acc": 97, "acc_other": 80, "ph": 98, "ph_other": 52,
                         "votes": "phoneme:intended word:intended recognition:intended", "recognised": "day",
                         "ms": 590, "attempts": 1}}},
            {"i": 2, "contrast": "b/v", "pair": "b/v:berry-very", "a": "berry", "b": "very", "points": 1, "level": 1,
             "words": {
                 "berry": {"heard": "berry", "acc": 90, "acc_other": 84, "ph": 88, "ph_other": 80,
                           "votes": "phoneme:none word:none recognition:intended", "recognised": "berry",
                           "ms": 710, "attempts": 1},
                 "very": {"heard": "?", "acc": 78, "acc_other": 76, "ph": 70, "ph_other": 72,
                          "votes": "phoneme:none word:none recognition:none", "recognised": None,
                          "ms": 680, "attempts": 2}}},
        ],
        "summary": {
            "trials": 3, "correct": 2, "pct": 0.667,
            "untrained_trials": 2, "untrained_correct": 2, "untrained_pct": 1.0,
            "duration_s": 60, "perception_duration_s": 30, "mean_rt_ms": 1000, "untrained_shortfall": 0,
            "contrasts": {
                "th": {"trials": 2, "correct": 2, "untrained_trials": 2, "untrained_correct": 2, "mean_rt_ms": 900},
                "b/v": {"trials": 1, "correct": 0, "untrained_trials": 0, "untrained_correct": 0, "mean_rt_ms": 1200},
            },
            "production": {"pairs": 2, "points": 2, "max_points": 4, "pct": 0.5, "duration_s": 25,
                           "contrasts": {"th": {"pairs": 1, "points": 1}, "b/v": {"pairs": 1, "points": 1}}},
        },
    }


def _old_session():
    """A session from an app version before levels and Say it (must still pass)."""
    s = _valid_session()
    del s["levels"], s["production"], s["summary"]["production"], s["summary"]["perception_duration_s"]
    return s


def _valid_state():
    return {
        "version": 1, "updated": "2026-09-01T08:01:01Z", "app_version": "0.1.0",
        "catalog_version": "2026-09-11.1", "sessions_completed": 1, "streak_days": 1,
        "last_session": "2026-09-01T08:00:00Z", "plan_source": "default",
        "contrasts": {"th": {"trials": 2, "correct": 2, "untrained_trials": 2, "untrained_correct": 2,
                             "last_pct": 1.0, "last_untrained_pct": 1.0, "recent_untrained_pct": [1.0],
                             "mean_rt_ms": 900, "words_trained": 2, "words_total": 90,
                             "level": 2, "level_changed": "2026-08-30T08:01:00Z",
                             "production_pairs": 1, "production_points": 1,
                             "last_production_pct": 0.5, "recent_production_pct": [0.5]},
                      "b/v": {"trials": 1, "correct": 0, "untrained_trials": 0, "untrained_correct": 0,
                              "last_pct": 0.0, "last_untrained_pct": None, "recent_untrained_pct": [],
                              "mean_rt_ms": 1200, "words_trained": 1, "words_total": 30,
                              "level": 1, "level_changed": None,
                              "production_pairs": 0, "production_points": 0,
                              "last_production_pct": None, "recent_production_pct": []}},
        "words": {"true": {"exposures": 1, "correct": 1, "last": "2026-09-01T08:00:10Z"}},
        "pairs": {"th:they-day": {"attempts": 2, "last_points": 1, "best": 2, "fails": 1,
                                  "last": "2026-09-01T08:00:50Z"}},
        "practice": {"longest_streak": 4, "total_seconds": 600,
                     "days": {"2026-09-01": {"sessions": 1, "seconds": 60, "perception_trials": 3,
                                             "production_pairs": 2}}},
    }


def _old_state():
    s = _valid_state()
    del s["pairs"], s["practice"]
    for k in STATE_PRODUCTION_KEYS:
        for cs in s["contrasts"].values():
            del cs[k]
    return s


def _valid_plan():
    return {"version": 1, "written": "2026-08-31T18:00:00Z", "written_by": "coach", "note": "",
            "trials_per_session": 40, "untrained_ratio": 0.5, "voices": ["en-GB-SoniaNeural"],
            "band": ["high", "mid"], "feedback": "full", "weights": {"th": 1.0, "s-cluster": 0.0},
            "production_pairs": 8, "production_threshold": 60, "max_level": 4, "levels": {"th": 2},
            "weekly_minutes_target": 20}


def _old_plan():
    p = _valid_plan()
    for k in ("production_pairs", "production_threshold", "max_level", "levels", "weekly_minutes_target"):
        del p[k]
    return p


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

        # files from before the level ladder and Say it are still valid
        old = os.path.join(tmp, "old")
        _write_folder(old, _old_plan(), _old_state(), [("20260901T080000Z.json", _old_session())])
        rep = validate_folder(old, catalog)
        assert rep.violations == [], rep.violations
        # a session that knows the block but skipped it
        skipped = os.path.join(tmp, "skipped")
        sess = _valid_session()
        sess["production"] = None
        sess["summary"]["production"] = None
        _write_folder(skipped, _valid_plan(), _valid_state(), [("20260901T080000Z.json", sess)])
        rep = validate_folder(skipped, catalog)
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
            # plan levers
            (lambda p, s, x: p.__setitem__("production_pairs", 31), "'production_pairs' must be"),
            (lambda p, s, x: p.__setitem__("production_threshold", 100.5), "'production_threshold' must be"),
            (lambda p, s, x: p.__setitem__("max_level", 5), "'max_level' must be"),
            (lambda p, s, x: p["levels"].__setitem__("th", 0), "levels['th'] must be"),
            (lambda p, s, x: p.__setitem__("weekly_minutes_target", -1), "'weekly_minutes_target' must be"),
            # state: level ladder, Say-it totals, pairs, practice
            (lambda p, s, x: s["contrasts"]["th"].__setitem__("level", 5), "'level' must be"),
            (lambda p, s, x: s["contrasts"]["th"].pop("level_changed"), "missing key 'level_changed'"),
            (lambda p, s, x: s["contrasts"]["th"].__setitem__("production_points", 3),
             "production_points (3) exceeds 2 x production_pairs"),
            (lambda p, s, x: s["contrasts"]["b/v"].__setitem__("last_production_pct", 0.5),
             "last_production_pct must be null when production_pairs is 0"),
            (lambda p, s, x: s["contrasts"]["th"].__setitem__("last_production_pct", None),
             "last_production_pct is null but production_pairs is 1"),
            (lambda p, s, x: s["contrasts"]["th"].__setitem__("recent_production_pct", [0.9]),
             "differs from the last recent_production_pct"),
            (lambda p, s, x: s["pairs"].__setitem__("zz:a-b", s["pairs"].pop("th:they-day")), "unknown contrast id"),
            (lambda p, s, x: s["pairs"]["th:they-day"].__setitem__("last_points", 3), "'last_points' must be"),
            (lambda p, s, x: s["pairs"]["th:they-day"].__setitem__("best", 0), "last_points (1) exceeds best"),
            (lambda p, s, x: s["pairs"]["th:they-day"].__setitem__("fails", 0), "a fail) but fails is 0"),
            (lambda p, s, x: s["pairs"]["th:they-day"].__setitem__("last", "yesterday"), "'last' must be a UTC"),
            (lambda p, s, x: s["practice"].__setitem__("longest_streak", 0), "longest_streak (0) is below streak_days"),
            (lambda p, s, x: s["practice"]["days"].__setitem__("2026-9-1", s["practice"]["days"].pop("2026-09-01")),
             "key must be a UTC date"),
            (lambda p, s, x: s["practice"]["days"]["2026-09-01"].__setitem__("seconds", 900),
             "days sum to 900 seconds but total_seconds is 600"),
            (lambda p, s, x: s["practice"]["days"]["2026-09-01"].pop("production_pairs"), "missing key 'production_pairs'"),
            # session: levels and the Say-it block
            (lambda p, s, x: x["levels"].pop("b/v"), "has trials but no entry in levels"),
            (lambda p, s, x: x["levels"].__setitem__("th", 9), "levels['th'] must be"),
            (lambda p, s, x: x.pop("production"), "missing key 'production'"),
            (lambda p, s, x: x["summary"].pop("production"), "summary: missing key 'production'"),
            (lambda p, s, x: x.__setitem__("production", None), "production must be null when the session's production is null"),
            (lambda p, s, x: x["summary"].__setitem__("production", None), "production must be an object"),
            (lambda p, s, x: x["summary"].__setitem__("perception_duration_s", 61), "perception_duration_s (61) exceeds"),
            (lambda p, s, x: x["production"][1].__setitem__("i", 3), "'i' is 3, expected 2"),
            (lambda p, s, x: x["production"][0].__setitem__("level", 1), "level is 1 but the session's levels['th'] is 2"),
            (lambda p, s, x: x["production"][0].__setitem__("pair", "th:day-they"), "should be 'th:they-day'"),
            (lambda p, s, x: x["production"][1].update(pair="th:they-day", contrast="th", a="they", b="day"),
             "appears twice"),
            (lambda p, s, x: x["production"][0]["words"].__setitem__("sink", x["production"][0]["words"].pop("day")),
             "must be exactly a and b"),
            (lambda p, s, x: x["production"][0]["words"]["day"].__setitem__("heard", "sink"), "'heard' must be"),
            (lambda p, s, x: x["production"][0]["words"]["day"].__setitem__("acc", 101), "'acc' must be"),
            (lambda p, s, x: x["production"][0]["words"]["day"].__setitem__("votes", "phoneme:yes word:no"),
             "'votes' must be"),
            (lambda p, s, x: x["production"][0]["words"]["day"].__setitem__("votes",
                                                                             "phoneme:none word:intended recognition:intended"),
             "phoneme vote is none but ph 98 vs ph_other 52 gives intended"),
            (lambda p, s, x: x["production"][1]["words"]["berry"].__setitem__("votes",
                                                                               "phoneme:none word:intended recognition:intended"),
             "word vote is intended but acc 90 vs acc_other 84 gives none"),
            (lambda p, s, x: x["production"][0]["words"]["they"].__setitem__("votes",
                                                                              "phoneme:other word:other recognition:none"),
             "recognition vote is none but recognised is the other word"),
            (lambda p, s, x: x["production"][0]["words"]["they"].update(recognised="they", votes="phoneme:other word:other recognition:other"),
             "recognition vote is other but recognised is the intended word"),
            (lambda p, s, x: x["production"][0]["words"]["they"].__setitem__("heard", "they"),
             "heard is 'they' but the votes"),
            (lambda p, s, x: x["production"][0]["words"]["they"].__setitem__("recognised", "Day."),
             "must be lower-cased with punctuation stripped"),
            (lambda p, s, x: x["production"][0]["words"]["they"].__setitem__("attempts", 3), "'attempts' must be"),
            (lambda p, s, x: x["production"][1]["words"]["very"].__setitem__("votes", "phoneme:none word:none recognition:other"),
             "recognition vote is other but nothing was recognised"),
            (lambda p, s, x: (x["production"][0].__setitem__("points", 2),
                              x["summary"]["production"].update(points=3, pct=0.75),
                              x["summary"]["production"]["contrasts"]["th"].__setitem__("points", 2)),
             "points is 2 but only 1 word(s) were heard as intended"),
            (lambda p, s, x: (x["production"][0].__setitem__("points", 0), x["production"][0]["words"]["day"].__setitem__("acc", 100),
                              x["summary"]["production"].update(points=1, pct=0.25),
                              x["summary"]["production"]["contrasts"]["th"].__setitem__("points", 0)),
             "heard as intended at accuracy 100 must score"),
            (lambda p, s, x: x["summary"]["production"].__setitem__("points", 3), "points is 3 but the rows sum to 2"),
            (lambda p, s, x: x["summary"]["production"].__setitem__("max_points", 6), "max_points is 6 but"),
            (lambda p, s, x: x["summary"]["production"].__setitem__("pct", 0.75), "pct is 0.75 but points/max_points"),
            (lambda p, s, x: x["summary"]["production"].__setitem__("duration_s", 40),
             "duration_s (40) plus perception_duration_s (30) exceeds"),
            (lambda p, s, x: x["summary"]["production"]["contrasts"].pop("b/v"), "differ from the contrasts in the production rows"),
            (lambda p, s, x: x["summary"]["production"]["contrasts"]["th"].__setitem__("pairs", 2), "pairs is 2 but the rows say 1"),
        ]
        for n, (mutate, fragment) in enumerate(cases):
            _expect(os.path.join(tmp, "case%d" % n), mutate, fragment)

        # long-back / schwa skip the phoneme signal
        def _long_back(p, s, x):
            row = x["production"][1]
            row.update(contrast="long-back", pair="long-back:had-hard", a="had", b="hard")
            row["words"] = {
                "had": {"heard": "had", "acc": 90, "acc_other": 70, "ph": None, "ph_other": None,
                        "votes": "phoneme:none word:intended recognition:intended", "recognised": "had",
                        "ms": 500, "attempts": 1},
                "hard": {"heard": "hard", "acc": 88, "acc_other": 60, "ph": 90, "ph_other": 40,
                         "votes": "phoneme:intended word:intended recognition:intended", "recognised": "hard",
                         "ms": 500, "attempts": 1}}
            row["points"] = 2
            x["levels"]["long-back"] = 1
            x["summary"]["production"].update(points=3, pct=0.75)
            x["summary"]["production"]["contrasts"] = {"th": {"pairs": 1, "points": 1}, "long-back": {"pairs": 1, "points": 2}}
        rep = _expect(os.path.join(tmp, "lb"), _long_back, "phoneme vote must be none for contrast 'long-back'", catalog)
        assert any("ph and ph_other must be null" in v for v in rep.violations), rep.violations
        assert not any("word 'had'" in v for v in rep.violations), rep.violations

        # insertion contrasts (-ed, h): the phoneme lives in one word only and votes on that score
        def _insertion(points, call_vote, called_vote, call_ph_other, called_ph):
            def mutate(p, s, x):
                row = x["production"][1]
                row.update(contrast="-ed", pair="-ed:call-called", a="call", b="called")
                row["words"] = {
                    "call": {"heard": "call", "acc": 95, "acc_other": 70, "ph": None, "ph_other": call_ph_other,
                             "votes": call_vote, "recognised": "call", "ms": 500, "attempts": 1},
                    "called": {"heard": "called", "acc": 96, "acc_other": 70, "ph": called_ph, "ph_other": None,
                               "votes": called_vote, "recognised": "called", "ms": 500, "attempts": 1}}
                row["points"] = points
                x["levels"]["-ed"] = 1
                x["summary"]["production"].update(points=1 + points, pct=round((1 + points) / 4.0, 4))
                x["summary"]["production"]["contrasts"] = {"th": {"pairs": 1, "points": 1}, "-ed": {"pairs": 1, "points": points}}
            return mutate

        good_ed = os.path.join(tmp, "ed-good")
        plan, state, sess = _valid_plan(), _valid_state(), _valid_session()
        _insertion(2, "phoneme:intended word:intended recognition:intended",
                   "phoneme:intended word:intended recognition:intended", 12, 97)(plan, state, sess)
        _write_folder(good_ed, plan, state, [("20260901T080000Z.json", sess)])
        rep = validate_folder(good_ed, catalog)
        assert rep.violations == [], rep.violations
        # a score in the 40-60 band cannot vote …
        _expect(os.path.join(tmp, "ed1"),
                _insertion(2, "phoneme:intended word:intended recognition:intended",
                           "phoneme:intended word:intended recognition:intended", 12, 50),
                "phoneme vote is intended but ph 50 / ph_other None gives none", catalog)
        # … a high score under the other reference votes for the other word …
        _expect(os.path.join(tmp, "ed2"),
                _insertion(2, "phoneme:intended word:intended recognition:intended",
                           "phoneme:intended word:intended recognition:intended", 88, 97),
                "phoneme vote is intended but ph None / ph_other 88 gives other", catalog)
        # … and both references cannot carry it
        _expect(os.path.join(tmp, "ed3"),
                lambda p, s, x: (_insertion(2, "phoneme:intended word:intended recognition:intended",
                                            "phoneme:intended word:intended recognition:intended", 12, 97)(p, s, x),
                                 x["production"][1]["words"]["called"].__setitem__("ph_other", 30)),
                "ph and ph_other cannot both be scores", catalog)

        # catalog-aware checks
        if catalog is not None:
            _expect(os.path.join(tmp, "cat1"),
                    lambda p, s, x: (x["trials"][0].update(pair="th:zzz-yyy", target="zzz", other="yyy", chosen="zzz")),
                    "is not in the catalog", catalog)
            _expect(os.path.join(tmp, "cat2"),
                    lambda p, s, x: x["production"][0].update(pair="th:zzz-yyy", a="zzz", b="yyy",
                                                              words={"zzz": x["production"][0]["words"]["they"],
                                                                     "yyy": x["production"][0]["words"]["day"]}),
                    "pair 'th:zzz-yyy' is not in the catalog", catalog)
            _expect(os.path.join(tmp, "cat3"),
                    lambda p, s, x: s["pairs"].__setitem__("th:zzz-yyy", s["pairs"].pop("th:they-day")),
                    "pair 'th:zzz-yyy' is not in the catalog", catalog)

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
