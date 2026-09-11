#!/usr/bin/env python3
"""Build data/catalog/catalog.json from the vendored Britfone + en_50k sources.

Usage:
  build-catalog.py            build the catalog and print a per-contrast table
  build-catalog.py --check    validate the existing catalog (exit 1 on violation)
  build-catalog.py --selftest run the unit tests (exit 1 on failure)

Options:
  --version V   catalog version string YYYY-MM-DD.N (default: derived, see below)
  --repo DIR    repository root (default: parent of this script's directory)

Default version: today.1, unless data/catalog/catalog-version.txt already holds
today's date; then the existing version is kept when the catalog content is
unchanged and N is bumped otherwise. The output is deterministic: sorted keys,
stable pair ordering, indent=1, UTF-8 with no ASCII escaping.

Standard library only. The rules implemented here are those of docs/DESIGN.md,
section "Catalog".
"""

import argparse
import datetime as _dt
import json
import os
import re
import sys

STRESS = "ˈˌ"
STRUT_SRC = "ɐ"   # Britfone writes STRUT as ɐ ...
STRUT_OUT = "ʌ"   # ... the catalog writes it ʌ

VOICES = [
    "en-GB-SoniaNeural", "en-GB-LibbyNeural", "en-GB-HollieNeural",
    "en-GB-RyanNeural", "en-GB-ThomasNeural", "en-GB-AlfieNeural",
]

SOURCES = {
    "pronunciation": "Britfone 3.0.1 (MIT)",
    "frequency": "hermitdave/FrequencyWords en_50k 2018 (MIT)",
}

# Full vowels for the schwa contrast (Britfone spelling, ɐ not ʌ). ɪ and the
# happy vowel i are weak vowels and deliberately not in this set.
FULL_VOWELS = [
    "æ", "ɛ", "ɒ", "ʊ", "ɐ", "ɑː", "ɔː", "iː", "uː", "ɜː",
    "eɪ", "əʊ", "aɪ", "aʊ", "ɔɪ", "ɪə", "ɛə", "ʊə",
]

# Contrast definitions. "variants" are (a, b) Britfone phoneme pairs; "" is an
# absent phoneme (insertion contrasts). Pair order: a is always the first
# phoneme of the contrast's phonemes list.
CONTRASTS = [
    {
        "id": "th", "label": "think / sink", "kind": "consonant",
        "phonemes": ["θ", "ð", "t", "s", "d"], "default_weight": 1.0,
        "variants": [("θ", "t"), ("θ", "s"), ("ð", "d")],
        "description": "The dental fricatives θ and ð against t, s and d. "
                       "Spanish θ/d are close but English d is a plosive.",
    },
    {
        "id": "s/z", "label": "ice / eyes", "kind": "consonant",
        "phonemes": ["s", "z"], "default_weight": 0.8,
        "variants": [("s", "z")],
        "description": "Voiceless s versus voiced z. Spanish has no z phoneme.",
    },
    {
        "id": "i/ii", "label": "ship / sheep", "kind": "vowel",
        "phonemes": ["ɪ", "iː"], "default_weight": 0.6,
        "variants": [("ɪ", "iː")],
        "description": "Short ɪ versus long iː. Spanish has one /i/.",
    },
    {
        "id": "b/v", "label": "berry / very", "kind": "consonant",
        "phonemes": ["b", "v"], "default_weight": 0.5,
        "variants": [("b", "v")],
        "description": "Plosive b versus fricative v. Spanish merges them.",
    },
    {
        "id": "cat/cut", "label": "cat / cut", "kind": "vowel",
        "phonemes": ["æ", "ʌ"], "default_weight": 0.4,
        "variants": [("æ", "ɐ")],
        "description": "TRAP æ versus STRUT ʌ. Both sit near Spanish /a/.",
    },
    {
        "id": "long-back", "label": "cot / caught", "kind": "vowel",
        "phonemes": ["ɒ", "ɔː", "æ", "ɑː"], "default_weight": 0.3,
        "variants": [("ɒ", "ɔː"), ("æ", "ɑː")],
        "description": "Short versus long back vowels: ɒ/ɔː (cot/caught) and "
                       "æ/ɑː (cat/cart).",
    },
    {
        "id": "j/y", "label": "jet / yet", "kind": "consonant",
        "phonemes": ["dʒ", "j"], "default_weight": 0.3,
        "variants": [("dʒ", "j")],
        "description": "Affricate dʒ versus approximant j. Spanish y/ll vary "
                       "between the two.",
    },
    {
        "id": "-ed", "label": "walk / walked", "kind": "ending",
        "phonemes": ["", "t", "d"], "default_weight": 0.3,
        "variants": [("", "t"), ("", "d")],
        "description": "Final -ed pronounced as a single t or d, present versus "
                       "absent. Not the extra-syllable ɪd of wanted.",
    },
    {
        "id": "h", "label": "eat / heat", "kind": "consonant",
        "phonemes": ["", "h"], "default_weight": 0.2,
        "variants": [("", "h")],
        "description": "Initial h present versus absent. Spanish h is silent.",
    },
    {
        "id": "sh/ch", "label": "ship / chip", "kind": "consonant",
        "phonemes": ["ʃ", "tʃ"], "default_weight": 0.2,
        "variants": [("ʃ", "tʃ")],
        "description": "Fricative ʃ versus affricate tʃ. Spanish has only ch.",
    },
    {
        "id": "er/or", "label": "work / walk", "kind": "vowel",
        "phonemes": ["ɜː", "ɔː"], "default_weight": 0.2,
        "variants": [("ɜː", "ɔː")],
        "description": "NURSE ɜː versus THOUGHT ɔː; both are spelled with r "
                       "in ways Spanish reads as o or e.",
    },
    {
        "id": "schwa", "label": "weak vowel", "kind": "vowel",
        "phonemes": ["ə"] + [STRUT_OUT if v == STRUT_SRC else v for v in FULL_VOWELS],
        "default_weight": 0.15,
        "variants": [("ə", v) for v in FULL_VOWELS],
        "description": "Schwa versus a full vowel in an unstressed syllable. "
                       "Spanish never reduces vowels.",
    },
    {
        "id": "s-cluster", "label": "school / eschool", "kind": "consonant",
        "phonemes": [], "default_weight": 0.0, "production_only": True,
        "variants": [],
        "description": "Initial s + consonant without a prosthetic e. "
                       "Production only: no listening pairs.",
    },
]

# Exclusion list (trainable only; the pairs stay in the catalog).
EXCLUDE = set("""
fuck fucking fucked fucker shit shite crap cunt cock dick prick piss pissed
ass arse arsehole asshole bitch bastard twat wank wanker slut whore nigger
negro fag faggot spastic retard retarded tit tits bollocks bugger bloody
damn cum pussy dyke paki coon queer spaz sod turd whores bitches fanny shat
spunk slag wop rape raped fart pees jew jews
""".split())
# single letters and letter names
EXCLUDE |= set("abcdefghijklmnopqrstuvwxyz")
EXCLUDE |= set("""
cee dee gee jay kay el em en pee cue ar ess tee vee ex wye zed zee aitch
""".split())
# abbreviations, interjections, filler
EXCLUDE |= set("""
mr mrs ms dr st jr sr etc vs ok pm am tv uk usa eu un id cd dvd pc bbc nhs
ie eg ltd inc co oz lb kg km cm mm ft mph gb mb kb phd ceo faq url www com
org http hmm um uh er ah eh mm ooh ugh oi ow aw duh yo yous sic til pic pics
mic biz sim sims mil bi vid cos wanna raf thru els tic stat stats ante
anti mac cam cams mag mam nan yup yuck yum fella feller aye howdy
""".split())
# proper nouns (names, places, brands, nationalities): Britfone and en_50k
# carry no case, so these are listed by hand from the trainable word set
EXCLUDE |= set("""
ali andy anna audi avon bart bern brett bruce burt carrie cassie cathy chad
dan eddie ellen essex evans germain germaine hackney haiti hannah harley
harlow harry hart helen hugh jacques kath kathy kirk kurt levi lorna lyn lynn
madge marc marcus marge martha marx matt max mick morse murray nat neil ollie
pam paul perth pete phil potts rachel rick ross russian ruth sally sam san
saturn sean shane shaun shaw shirley sid sikh sioux sofia sutton thai tim turk
warner yale
""".split())
# heteronyms whose TTS rendering is ambiguous (those with two Britfone
# variants are skipped automatically; this list covers the rest too)
EXCLUDE |= set("""
read lead live use close bass wind tear bow row wound sow does minute object
record present produce content contract desert project permit subject
conduct convert increase insult rebel refuse suspect export import progress
protest house excuse abuse mouth entrance invalid resume separate estimate
graduate alternate appropriate associate delegate duplicate moderate
elaborate advocate intimate polish moped axes bases console compact
compound conflict contest contrast converse decrease defect digest discount
escort extract impact incline intern perfect pervert proceeds recall recess
recount refund research reject relay rewrite survey transfer transplant
transport update upgrade upset address combat compress insert frequent
attribute addict ally annex coordinate deliberate degenerate initiate
predicate articulate certificate syndicate aggregate approximate
subordinate blessed aged beloved crooked dogged jagged ragged rugged wicked
wretched supposed used cursed legged learned putting sewer buffet number
resign tier lower august mobile nice
""".split())

BANDS = [("high", 2000), ("mid", 8000), ("low", 50000)]
RARE_RANK = 10 ** 6  # sort key for words not in en_50k

WORD_RE = re.compile(r"^[a-z]+$")
ENTRY_RE = re.compile(r"^(.*?)(?:\((\d+)\))?$")


# --------------------------------------------------------------------------
# phoneme helpers

def tokenize(pron):
    """'ʃ ˈiː p' -> ['ʃ', 'ˈiː', 'p'] (stress marks kept on their vowel)."""
    return [t for t in pron.strip().split(" ") if t]


def strip_stress(tokens):
    return [t.lstrip(STRESS) for t in tokens]


def is_stressed(token):
    return token[:1] in STRESS


def out_phoneme(p):
    """Catalog spelling of a stripped Britfone phoneme (ɐ -> ʌ)."""
    return STRUT_OUT if p == STRUT_SRC else p


def to_ipa(tokens):
    """Emitted ipa: tokens joined, stress kept, ɐ written ʌ."""
    return "".join(t.replace(STRUT_SRC, STRUT_OUT) for t in tokens)


def minimal_diff(ta, tb):
    """Compare two stress-stripped token lists.

    Returns ("sub", slot, x, y) when they differ in exactly one slot by
    substitution, ("ins", slot, "", y) when tb is ta with one token inserted
    at the start or the end, None otherwise. tb must be the longer side.
    """
    if len(ta) == len(tb):
        slots = [i for i in range(len(ta)) if ta[i] != tb[i]]
        if len(slots) == 1:
            i = slots[0]
            return ("sub", i, ta[i], tb[i])
        return None
    if len(tb) == len(ta) + 1:
        if tb[:-1] == ta:
            return ("ins", len(tb) - 1, "", tb[-1])
        if tb[1:] == ta:
            return ("ins", 0, "", tb[0])
    return None


def position_of(slot, length):
    if slot == 0:
        return "initial"
    if slot == length - 1:
        return "final"
    return "medial"


# base+d forms that are not the past tense of the base (see/seed) or are
# nonstandard in British English (waked, slayed, shined): not -ed pairs.
ED_NOT_PAST = set("seed feed weed waked slayed shined".split())


def ed_spelling_ok(base, longer, lexicon=None):
    """Is `longer` the regular -ed spelling of `base`?

    `lexicon` (a set of words) lets the -ied form be claimed by its -y base
    when both exist (carried belongs to carry, not carrie).
    """
    if longer in ED_NOT_PAST:
        return False
    if base.endswith("e"):
        if longer != base + "d":                    # bake/baked, die/died
            return False
        if base.endswith("ie") and lexicon is not None and base[:-2] + "y" in lexicon:
            return False                            # carrie/carried
        return True
    if longer == base + "ed":                       # walk/walked, learn/learned
        return True
    if len(base) >= 2 and base[-1] not in "aeiouy" and longer == base + base[-1] + "ed":
        return True                                 # stop/stopped
    if base.endswith("y") and longer == base[:-1] + "ied":
        return True                                 # try/tried
    return False


def classify_pair(contrast, a_tokens, b_tokens, a_word=None, b_word=None, lexicon=None):
    """Decide whether (a, b) is a minimal pair of `contrast`.

    a_tokens/b_tokens are Britfone tokens with stress marks. Returns
    (x, y, slot, variant_len) with x, y the raw Britfone phonemes at the
    differing slot, or None.
    """
    sa, sb = strip_stress(a_tokens), strip_stress(b_tokens)
    d = minimal_diff(sa, sb)
    if d is None:
        return None
    kind, slot, x, y = d
    cid = contrast["id"]
    if cid == "-ed":
        if kind != "ins" or slot != len(sb) - 1 or y not in ("t", "d"):
            return None
        if a_word is not None and not ed_spelling_ok(a_word, b_word, lexicon):
            return None
        return (x, y, slot, len(sb))
    if cid == "h":
        if kind != "ins" or slot != 0 or y != "h":
            return None
        return (x, y, slot, len(sb))
    if kind != "sub":
        return None
    if cid == "schwa":
        if x != "ə" or y not in FULL_VOWELS:
            return None
        if is_stressed(a_tokens[slot]) or is_stressed(b_tokens[slot]):
            return None
        return (x, y, slot, len(sb))
    if (x, y) in contrast["variants"]:
        return (x, y, slot, len(sb))
    return None


# --------------------------------------------------------------------------
# sources

def load_britfone(path):
    """Return list of (word, variant_no or None, tokens) and dict word -> count."""
    entries = []
    counts = {}
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            line = line.rstrip("\n")
            if not line or ", " not in line:
                continue
            name, pron = line.split(", ", 1)
            m = ENTRY_RE.match(name)
            word = m.group(1).lower()
            variant = m.group(2)
            entries.append((word, variant, tokenize(pron)))
            counts[word] = counts.get(word, 0) + 1
    return entries, counts


def load_ranks(path):
    ranks = {}
    with open(path, encoding="utf-8") as fh:
        for n, line in enumerate(fh, 1):
            parts = line.split()
            if not parts:
                continue
            w = parts[0]
            if WORD_RE.match(w) and w not in ranks:
                ranks[w] = n
    return ranks


def band_of(rank):
    if rank is None:
        return "rare"
    for name, limit in BANDS:
        if rank <= limit:
            return name
    return "rare"


def word_trainable(word, ranks, counts):
    return (WORD_RE.match(word) is not None and word in ranks
            and counts.get(word, 0) == 1 and word not in EXCLUDE)


# --------------------------------------------------------------------------
# build

def build_pairs(contrast, entries, counts, ranks):
    if contrast.get("production_only"):
        return []
    # index: stripped token tuple -> list of (word, tokens)
    index = {}
    words_ok = [(w, toks) for (w, v, toks) in entries if WORD_RE.match(w)]
    for w, toks in words_ok:
        index.setdefault(tuple(strip_stress(toks)), []).append((w, toks))

    cid = contrast["id"]
    lexicon = set(w for w, _ in words_ok)
    found = {}
    for a_word, a_toks in words_ok:
        sa = strip_stress(a_toks)
        candidates = []
        if cid == "-ed":
            for y in ("t", "d"):
                candidates.append(tuple(sa + [y]))
        elif cid == "h":
            if sa[:1] != ["h"]:
                candidates.append(tuple(["h"] + sa))
        else:
            for x, y in contrast["variants"]:
                for i, p in enumerate(sa):
                    if p == x:
                        candidates.append(tuple(sa[:i] + [y] + sa[i + 1:]))
        for key in candidates:
            for b_word, b_toks in index.get(key, ()):
                if b_word == a_word:
                    continue
                r = classify_pair(contrast, a_toks, b_toks, a_word, b_word, lexicon)
                if r is None:
                    continue
                pid = "%s:%s-%s" % (cid, a_word, b_word)
                if pid in found:
                    continue
                x, y, slot, length = r
                found[pid] = make_pair(pid, a_word, a_toks, b_word, b_toks,
                                       x, y, slot, length, ranks, counts)
    pairs = list(found.values())
    pairs.sort(key=lambda p: (rank_key(p["a"]["rank"]) + rank_key(p["b"]["rank"]), p["id"]))
    return pairs


def rank_key(rank):
    return RARE_RANK if rank is None else rank


def make_pair(pid, a_word, a_toks, b_word, b_toks, x, y, slot, length, ranks, counts):
    def side(word, toks):
        rank = ranks.get(word)
        return {"word": word, "ipa": to_ipa(toks), "rank": rank, "band": band_of(rank)}
    return {
        "id": pid,
        "a": side(a_word, a_toks),
        "b": side(b_word, b_toks),
        "diff": [out_phoneme(x), out_phoneme(y)],
        "variant": "%s/%s" % (out_phoneme(x), out_phoneme(y)),
        "position": position_of(slot, length),
        "trainable": word_trainable(a_word, ranks, counts) and word_trainable(b_word, ranks, counts),
    }


def build_catalog(entries, counts, ranks):
    contrasts = []
    for c in CONTRASTS:
        pairs = build_pairs(c, entries, counts, ranks)
        n_train = sum(1 for p in pairs if p["trainable"])
        production_only = bool(c.get("production_only"))
        trainable = not production_only and n_train >= 6 if c["id"] == "schwa" \
            else not production_only and n_train > 0
        contrasts.append({
            "id": c["id"],
            "label": c["label"],
            "kind": c["kind"],
            "phonemes": c["phonemes"],
            "default_weight": c["default_weight"],
            "trainable": trainable,
            "production_only": production_only,
            "description": c["description"],
            "trainable_pairs": n_train,
            "total_pairs": len(pairs),
            "pairs": pairs,
        })
    return {"sources": SOURCES, "voices": VOICES, "contrasts": contrasts}


def dump(obj):
    return json.dumps(obj, ensure_ascii=False, sort_keys=True, indent=1) + "\n"


def content_key(catalog):
    return dump({k: v for k, v in catalog.items() if k not in ("version", "generated")})


def decide_version(existing_version, existing_catalog, new_catalog, today):
    """Keep the old version when the content is unchanged, else today.N."""
    if existing_version and existing_catalog is not None:
        if content_key(existing_catalog) == content_key(new_catalog):
            return existing_version, existing_catalog.get("generated")
        m = re.match(r"^(\d{4}-\d{2}-\d{2})\.(\d+)$", existing_version)
        if m and m.group(1) == today:
            return "%s.%d" % (today, int(m.group(2)) + 1), None
    return today + ".1", None


def distinct_ipa_pairs(pairs):
    """Number of distinct (ipa a, ipa b) sound pairs among the trainable pairs
    (homophone spellings such as sir/saw, sir/sore share one sound pair)."""
    return len(set((p["a"]["ipa"], p["b"]["ipa"]) for p in pairs if p["trainable"]))


def print_table(catalog, out=sys.stdout):
    words = set()
    print("%-10s %9s %8s %6s  %s" % ("contrast", "trainable", "distinct", "total", "trainable"), file=out)
    for c in catalog["contrasts"]:
        for p in c["pairs"]:
            if p["trainable"]:
                words.add(p["a"]["word"])
                words.add(p["b"]["word"])
        print("%-10s %9d %8d %6d  %s" % (c["id"], c["trainable_pairs"], distinct_ipa_pairs(c["pairs"]),
                                         c["total_pairs"], "yes" if c["trainable"] else "no"), file=out)
    print("distinct trainable words: %d  (distinct = trainable pairs with different sounds, "
          "homophone spellings counted once)" % len(words), file=out)


def cmd_build(repo, version_arg):
    src = os.path.join(repo, "data", "sources")
    out_dir = os.path.join(repo, "data", "catalog")
    entries, counts = load_britfone(os.path.join(src, "britfone.main.3.0.1.csv"))
    ranks = load_ranks(os.path.join(src, "en_50k.txt"))
    catalog = build_catalog(entries, counts, ranks)

    cat_path = os.path.join(out_dir, "catalog.json")
    ver_path = os.path.join(out_dir, "catalog-version.txt")
    today = _dt.datetime.now(_dt.timezone.utc).strftime("%Y-%m-%d")
    existing_version = existing_catalog = None
    if os.path.exists(ver_path) and os.path.exists(cat_path):
        with open(ver_path, encoding="utf-8") as fh:
            existing_version = fh.read().strip()
        try:
            with open(cat_path, encoding="utf-8") as fh:
                existing_catalog = json.load(fh)
        except ValueError:
            existing_catalog = None
    if version_arg:
        version, generated = version_arg, None
        if existing_catalog is not None and existing_version == version_arg \
                and content_key(existing_catalog) == content_key(catalog):
            generated = existing_catalog.get("generated")
    else:
        version, generated = decide_version(existing_version, existing_catalog, catalog, today)
    if not generated:
        generated = _dt.datetime.now(_dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    catalog["version"] = version
    catalog["generated"] = generated

    os.makedirs(out_dir, exist_ok=True)
    with open(cat_path, "w", encoding="utf-8") as fh:
        fh.write(dump(catalog))
    with open(ver_path, "w", encoding="utf-8") as fh:
        fh.write(version + "\n")
    print_table(catalog)
    print("version %s, %d bytes -> %s" % (version, os.path.getsize(cat_path), cat_path))
    return 0


# --------------------------------------------------------------------------
# check

def cmd_check(repo):
    src = os.path.join(repo, "data", "sources")
    cat_path = os.path.join(repo, "data", "catalog", "catalog.json")
    ver_path = os.path.join(repo, "data", "catalog", "catalog-version.txt")
    problems = []
    try:
        with open(cat_path, encoding="utf-8") as fh:
            catalog = json.load(fh)
    except (OSError, ValueError) as e:
        print("check: cannot read %s: %s" % (cat_path, e))
        return 1
    entries, counts = load_britfone(os.path.join(src, "britfone.main.3.0.1.csv"))
    ranks = load_ranks(os.path.join(src, "en_50k.txt"))
    by_word = {}
    for w, v, toks in entries:
        by_word.setdefault(w, []).append(toks)
    by_id = {c["id"]: c for c in CONTRASTS}
    lexicon = set(w for w in by_word if WORD_RE.match(w))

    version = catalog.get("version", "")
    if not re.match(r"^\d{4}-\d{2}-\d{2}\.\d+$", version):
        problems.append("bad version string %r" % version)
    try:
        with open(ver_path, encoding="utf-8") as fh:
            if fh.read().strip() != version:
                problems.append("catalog-version.txt does not match catalog.json version")
    except OSError:
        problems.append("catalog-version.txt missing")

    seen_ids = set()
    seen_contrasts = set()
    for c in catalog.get("contrasts", []):
        cid = c.get("id")
        if cid in seen_contrasts:
            problems.append("duplicate contrast %s" % cid)
        seen_contrasts.add(cid)
        spec = by_id.get(cid)
        if spec is None:
            problems.append("unknown contrast %s" % cid)
            continue
        n_train = 0
        for p in c.get("pairs", []):
            pid = p.get("id", "")
            if pid in seen_ids:
                problems.append("duplicate pair id %s" % pid)
            seen_ids.add(pid)
            a, b = p.get("a", {}), p.get("b", {})
            aw, bw = a.get("word", ""), b.get("word", "")
            if not WORD_RE.match(aw) or not WORD_RE.match(bw):
                problems.append("%s: words not a-z" % pid)
                continue
            if aw == bw:
                problems.append("%s: identical words" % pid)
            if pid != "%s:%s-%s" % (cid, aw, bw):
                problems.append("%s: id does not match words" % pid)
            if not a.get("ipa") or not b.get("ipa"):
                problems.append("%s: empty ipa" % pid)
                continue
            a_toks = find_entry(by_word, aw, a["ipa"])
            b_toks = find_entry(by_word, bw, b["ipa"])
            if a_toks is None or b_toks is None:
                problems.append("%s: ipa not found in Britfone" % pid)
                continue
            r = classify_pair(spec, a_toks, b_toks, aw, bw, lexicon)
            if r is None:
                problems.append("%s: not a minimal pair of %s" % (pid, cid))
                continue
            x, y, slot, length = r
            if p.get("diff") != [out_phoneme(x), out_phoneme(y)]:
                problems.append("%s: diff mismatch" % pid)
            if p.get("position") != position_of(slot, length):
                problems.append("%s: position mismatch" % pid)
            for side, w in ((a, aw), (b, bw)):
                if side.get("band") != band_of(ranks.get(w)):
                    problems.append("%s: band mismatch for %s" % (pid, w))
            exp_train = word_trainable(aw, ranks, counts) and word_trainable(bw, ranks, counts)
            if bool(p.get("trainable")) != exp_train:
                problems.append("%s: trainable flag should be %s" % (pid, exp_train))
            if p.get("trainable"):
                n_train += 1
        if c.get("trainable") and n_train == 0:
            problems.append("contrast %s trainable but has no trainable pair" % cid)
        if cid == "schwa" and c.get("trainable") and n_train < 6:
            problems.append("schwa trainable with fewer than 6 pairs")
        if spec.get("production_only") and c.get("pairs"):
            problems.append("contrast %s is production-only but has pairs" % cid)
    missing = set(by_id) - seen_contrasts
    if missing:
        problems.append("missing contrasts: %s" % ", ".join(sorted(missing)))

    if problems:
        for p in problems:
            print("check: " + p)
        print("check: %d problem(s)" % len(problems))
        return 1
    print("check: ok (%d contrasts, %d pairs, version %s)"
          % (len(seen_contrasts), len(seen_ids), version))
    return 0


def find_entry(by_word, word, ipa):
    for toks in by_word.get(word, ()):
        if to_ipa(toks) == ipa:
            return toks
    return None


# --------------------------------------------------------------------------
# selftest

def selftest():
    failures = []

    def check(cond, msg):
        if not cond:
            failures.append(msg)

    # tokenisation and stress stripping
    check(tokenize("ʃ ˈiː p") == ["ʃ", "ˈiː", "p"], "tokenize")
    check(tokenize("tʃ ˈɪ p") == ["tʃ", "ˈɪ", "p"], "tokenize affricate is one token")
    check(strip_stress(["ʃ", "ˈiː", "p"]) == ["ʃ", "iː", "p"], "strip primary")
    check(strip_stress(["ˌæ", "b", "ə", "d", "ˈiː", "n"]) == ["æ", "b", "ə", "d", "iː", "n"], "strip secondary")
    check(is_stressed("ˈiː") and is_stressed("ˌæ") and not is_stressed("ə"), "is_stressed")

    # ɐ -> ʌ mapping
    check(to_ipa(["k", "ˈɐ", "t"]) == "kˈʌt", "ipa maps ɐ to ʌ and keeps stress")
    check(to_ipa(["ʃ", "ˈiː", "p"]) == "ʃˈiːp", "ipa joins without spaces")
    check(out_phoneme("ɐ") == "ʌ" and out_phoneme("æ") == "æ", "out_phoneme")

    # minimality
    lex = {
        "ship": "ʃ ˈɪ p", "sheep": "ʃ ˈiː p", "chip": "tʃ ˈɪ p",
        "walk": "w ˈɔː k", "walked": "w ˈɔː k t", "want": "w ˈɒ n t",
        "wanted": "w ˈɒ n t ɪ d", "play": "p l ˈeɪ", "played": "p l ˈeɪ d",
        "stop": "s t ˈɒ p", "stopped": "s t ˈɒ p t", "eat": "ˈiː t",
        "heat": "h ˈiː t", "cat": "k ˈæ t", "cut": "k ˈɐ t",
        "think": "θ ˈɪ ŋ k", "sink": "s ˈɪ ŋ k", "tank": "t ˈæ ŋ k",
        "ago": "ə g ˈəʊ", "ego": "ˈiː g əʊ", "affect": "ə f ˈɛ k t",
        "effect": "ɪ f ˈɛ k t", "sofa": "s ˈəʊ f ə", "sofar": "s ˈəʊ f ɑː",
        "ahead": "ə h ˈɛ d", "arrest": "ə ɹ ˈɛ s t", "unrest": "ɐ n ɹ ˈɛ s t",
        "seed": "s ˈiː d", "aha": "ɑː h ˈɑː", "bed": "b ˈɛ d", "see": "s ˈiː",
    }
    T = {w: tokenize(p) for w, p in lex.items()}
    C = {c["id"]: c for c in CONTRASTS}

    def pair(cid, a, b):
        return classify_pair(C[cid], T[a], T[b], a, b)

    r = pair("i/ii", "ship", "sheep")
    check(r is not None and r[:2] == ("ɪ", "iː") and r[2] == 1, "ship/sheep is i/ii")
    check(pair("i/ii", "ship", "chip") is None, "ship/chip is not i/ii")
    check(pair("sh/ch", "ship", "chip") is not None, "ship/chip is sh/ch")
    check(pair("sh/ch", "ship", "sheep") is None, "ship/sheep is not sh/ch")
    check(pair("-ed", "walk", "walked") == ("", "t", 3, 4), "walk/walked is -ed")
    check(pair("-ed", "play", "played") == ("", "d", 3, 4), "play/played is -ed")
    check(pair("-ed", "stop", "stopped") is not None, "stop/stopped is -ed (doubled consonant)")
    check(pair("-ed", "want", "wanted") is None, "want/wanted is not -ed")
    check(pair("-ed", "walked", "walk") is None, "-ed pair order: base first")
    check(ed_spelling_ok("bake", "baked") and ed_spelling_ok("try", "tried")
          and ed_spelling_ok("learn", "learned"), "ed spelling")
    check(not ed_spelling_ok("walk", "talked"), "ed spelling rejects unrelated")
    check(not ed_spelling_ok("we", "weed") and not ed_spelling_ok("he", "heed"), "e-final base takes only d")
    check(ed_spelling_ok("die", "died") and ed_spelling_ok("agree", "agreed"), "die/died, agree/agreed")
    check(not ed_spelling_ok("see", "seed") and not ed_spelling_ok("fee", "feed")
          and not ed_spelling_ok("wake", "waked") and not ed_spelling_ok("slay", "slayed"),
          "seed/feed are not past forms, waked/slayed nonstandard")
    check(ed_spelling_ok("free", "freed") and ed_spelling_ok("shine", "shined") is False, "freed ok, shined out")
    check(pair("-ed", "see", "seed") is None, "see/seed is not an -ed pair")
    check(not ed_spelling_ok("carrie", "carried", {"carrie", "carried", "carry"}), "carried belongs to carry")
    check(ed_spelling_ok("carrie", "carried", {"carrie", "carried"}), "carrie/carried without carry")
    check(pair("h", "eat", "heat") == ("", "h", 0, 3), "eat/heat is h")
    check(pair("h", "heat", "eat") is None, "h pair order")
    check(pair("cat/cut", "cat", "cut") == ("æ", "ɐ", 1, 3), "cat/cut")
    check(pair("th", "think", "sink") == ("θ", "s", 0, 4), "think/sink is th")
    check(pair("th", "think", "tank") is None, "think/tank differs in two slots")
    check(pair("schwa", "ago", "ego") is None, "ago/ego: stressed slot, not schwa")
    check(pair("schwa", "affect", "effect") is None, "affect/effect: ɪ is not full")
    check(pair("schwa", "arrest", "unrest") is None, "arrest/unrest: different lengths")
    check(pair("schwa", "sofa", "sofar") == ("ə", "ɑː", 3, 4), "sofa/sofar is schwa (unstressed full vowel)")
    check(pair("schwa", "sofar", "sofa") is None, "schwa pair order: ə side first")
    check(pair("schwa", "ahead", "aha") is None, "different lengths")
    check(minimal_diff(["s", "iː", "d"], ["s", "iː"]) is None, "shorter b is not insertion")
    check(minimal_diff(["iː", "t"], ["h", "iː", "t"]) == ("ins", 0, "", "h"), "initial insertion")
    check(minimal_diff(["w", "ɔː", "k"], ["w", "ɔː", "k", "t"]) == ("ins", 3, "", "t"), "final insertion")
    check(minimal_diff(["w", "ɔː", "k"], ["w", "ɔː", "t", "k"]) is None, "medial insertion rejected")

    # position
    check(position_of(0, 3) == "initial" and position_of(2, 3) == "final"
          and position_of(1, 3) == "medial", "position")

    # bands
    check(band_of(1) == "high" and band_of(2000) == "high" and band_of(2001) == "mid"
          and band_of(8000) == "mid" and band_of(8001) == "low" and band_of(50000) == "low"
          and band_of(None) == "rare", "band thresholds")

    # trainable rules
    ranks = {"ship": 1834, "sheep": 3120, "read": 400, "fuck": 300, "b": 10}
    counts = {"ship": 1, "sheep": 1, "read": 2, "fuck": 1, "b": 1, "zzz": 1}
    check(word_trainable("ship", ranks, counts), "trainable word")
    check(not word_trainable("read", ranks, counts), "homograph not trainable")
    check(not word_trainable("zzz", ranks, counts), "rare not trainable")
    check(not word_trainable("fuck", ranks, counts), "excluded not trainable")
    check(not word_trainable("b", ranks, counts), "letter not trainable")
    ranks2 = {"harry": 900, "hurry": 2500, "cos": 700, "wanna": 400, "rape": 3711, "app": 1500}
    counts2 = {w: 1 for w in ranks2}
    check(not word_trainable("harry", ranks2, counts2) and word_trainable("hurry", ranks2, counts2),
          "proper noun not trainable")
    check(not word_trainable("cos", ranks2, counts2) and not word_trainable("wanna", ranks2, counts2),
          "slang/abbreviation not trainable")
    check(not word_trainable("rape", ranks2, counts2), "sensitive word not trainable")
    check(word_trainable("app", ranks2, counts2), "app is ordinary vocabulary")
    check(distinct_ipa_pairs([
        {"a": {"ipa": "sˈɜː"}, "b": {"ipa": "sˈɔː"}, "trainable": True},
        {"a": {"ipa": "sˈɜː"}, "b": {"ipa": "sˈɔː"}, "trainable": True},
        {"a": {"ipa": "kˈɜːt"}, "b": {"ipa": "kˈɔːt"}, "trainable": True},
        {"a": {"ipa": "fˈɜː"}, "b": {"ipa": "fˈɔː"}, "trainable": False},
    ]) == 2, "distinct ipa pairs count homophones once")

    # end-to-end on the toy lexicon
    entries = [(w, None, T[w]) for w in sorted(lex)]
    cat = build_catalog(entries, counts | {w: 1 for w in lex}, ranks)
    by = {c["id"]: c for c in cat["contrasts"]}
    ids = [p["id"] for p in by["i/ii"]["pairs"]]
    check(ids == ["i/ii:ship-sheep"], "toy i/ii pairs: %r" % ids)
    p = by["i/ii"]["pairs"][0]
    check(p["a"]["ipa"] == "ʃˈɪp" and p["b"]["ipa"] == "ʃˈiːp", "toy ipa")
    check(p["diff"] == ["ɪ", "iː"] and p["variant"] == "ɪ/iː" and p["position"] == "medial", "toy pair fields")
    check(p["trainable"] and p["a"]["band"] == "high" and p["b"]["band"] == "mid", "toy trainable/bands")
    check(by["cat/cut"]["pairs"][0]["b"]["ipa"] == "kˈʌt", "toy ɐ mapping in pair")
    ed = [p["id"] for p in by["-ed"]["pairs"]]
    check(sorted(ed) == ["-ed:play-played", "-ed:stop-stopped", "-ed:walk-walked"], "toy -ed: %r" % ed)
    check([p["id"] for p in by["h"]["pairs"]] == ["h:eat-heat"], "toy h")
    check(by["h"]["pairs"][0]["diff"] == ["", "h"] and by["h"]["pairs"][0]["position"] == "initial", "toy h diff")
    check(by["schwa"]["trainable"] is False and by["schwa"]["total_pairs"] == 1, "toy schwa: <6 pairs -> not trainable")
    check(by["s-cluster"]["pairs"] == [] and by["s-cluster"]["production_only"], "s-cluster empty")
    check(by["th"]["pairs"][0]["id"] == "th:think-sink", "toy th")
    check(dump(cat) == dump(json.loads(dump(cat))), "dump round trip / deterministic")
    check(decide_version("2026-09-11.1", cat, cat, "2026-09-11") == ("2026-09-11.1", None), "version kept when unchanged")
    changed = json.loads(dump(cat))
    changed["voices"] = []
    check(decide_version("2026-09-11.1", cat, changed, "2026-09-11")[0] == "2026-09-11.2", "version bumped same day")
    check(decide_version("2026-09-10.3", cat, changed, "2026-09-11")[0] == "2026-09-11.1", "version resets next day")
    check(decide_version(None, None, cat, "2026-09-11")[0] == "2026-09-11.1", "version first build")

    if failures:
        for f in failures:
            print("selftest FAIL: " + f)
        print("selftest: %d failure(s)" % len(failures))
        return 1
    print("selftest: ok")
    return 0


# --------------------------------------------------------------------------

def main(argv):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--check", action="store_true", help="validate the existing catalog")
    ap.add_argument("--selftest", action="store_true", help="run the unit tests")
    ap.add_argument("--version", help="catalog version string YYYY-MM-DD.N")
    ap.add_argument("--repo", help="repository root")
    args = ap.parse_args(argv)
    repo = args.repo or os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    if args.selftest:
        return selftest()
    if args.check:
        return cmd_check(repo)
    if args.version and not re.match(r"^\d{4}-\d{2}-\d{2}\.\d+$", args.version):
        print("bad --version, expected YYYY-MM-DD.N")
        return 2
    return cmd_build(repo, args.version)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
