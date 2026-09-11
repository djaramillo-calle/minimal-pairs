#!/usr/bin/env python3
"""Render the clip pack: one Opus/WebM clip per trainable word per voice.

Reads AZURE_SPEECH_KEY and AZURE_SPEECH_REGION from the environment (never
printed, never written).  Output layout (docs/DESIGN.md "Clip pack"):

    <out>/index.json
    <out>/sha256.txt
    <out>/<voice>/<word>.webm

Idempotent: existing non-empty clips are kept.  Python 3 standard library only.

Examples:
    scripts/render-clips.py --placeholder              # tiny committed pack
    scripts/render-clips.py                            # full pack into clips/
    scripts/render-clips.py --install-assets --zip clips.zip
    scripts/render-clips.py --install-assets --contrasts th,s/z
    scripts/render-clips.py --dry-run --contrasts th
"""

import argparse
import concurrent.futures
import hashlib
import json
import os
import shutil
import sys
import threading
import time
import urllib.error
import urllib.request
import zipfile

DEFAULT_VOICES = [
    "en-GB-SoniaNeural",
    "en-GB-LibbyNeural",
    "en-GB-HollieNeural",
    "en-GB-RyanNeural",
    "en-GB-ThomasNeural",
    "en-GB-AlfieNeural",
]
DEFAULT_FORMAT = "webm-24khz-16bit-24kbps-mono-opus"
FORMAT_LABELS = {DEFAULT_FORMAT: "webm/opus 24 kHz 24 kbps mono"}
EBML_MAGIC = b"\x1a\x45\xdf\xa3"
MIN_CLIP_BYTES = 1500
SUSPICIOUS_CLIP_BYTES = 12000  # a single word never needs this much at 24 kbps
ATTEMPTS = 3
PLACEHOLDER_VOICES = ["en-GB-SoniaNeural", "en-GB-RyanNeural"]
PLACEHOLDER_CONTRASTS = ["th", "s/z", "i/ii"]
PLACEHOLDER_WORDS = 12
ASSETS_DIR = os.path.join("app", "src", "main", "assets", "clips")
PLACEHOLDER_DIR = os.path.join("data", "placeholder-clips", "clips")


def log(msg):
    print(msg, flush=True)


# ---------------------------------------------------------------- catalog


def load_catalog(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def trainable_words_by_contrast(catalog):
    """{contrast id: sorted list of words} over trainable pairs of trainable contrasts."""
    out = {}
    for c in catalog["contrasts"]:
        if not c.get("trainable", False):
            continue
        words = set()
        for p in c.get("pairs", []):
            if p.get("trainable", False):
                words.add(p["a"]["word"])
                words.add(p["b"]["word"])
        out[c["id"]] = sorted(words)
    return out


def select_words(catalog, contrasts=None, words=None, limit=None):
    """The words to render for this run, sorted."""
    by_contrast = trainable_words_by_contrast(catalog)
    if contrasts:
        unknown = [c for c in contrasts if c not in by_contrast]
        if unknown:
            raise SystemExit("unknown or non-trainable contrast(s): %s" % ", ".join(unknown))
        selected = set()
        for c in contrasts:
            selected.update(by_contrast[c])
    else:
        selected = set()
        for ws in by_contrast.values():
            selected.update(ws)
    if words:
        selected &= set(words)
        missing = set(words) - selected
        if missing:
            log("warning: not trainable words in the catalog, skipped: %s" % ", ".join(sorted(missing)))
    result = sorted(selected)
    if limit is not None:
        result = result[:limit]
    return result


def placeholder_words(catalog):
    """12 common words: the commonest trainable pairs of the top three contrasts."""
    pairs_by_id = {c["id"]: c["pairs"] for c in catalog["contrasts"]}
    words = []
    per_contrast = PLACEHOLDER_WORDS // len(PLACEHOLDER_CONTRASTS)
    for cid in PLACEHOLDER_CONTRASTS:
        taken = []
        for p in pairs_by_id.get(cid, []):  # already sorted commonest first
            if not p.get("trainable", False):
                continue
            for w in (p["a"]["word"], p["b"]["word"]):
                if w not in words and w not in taken:
                    taken.append(w)
            if len(taken) >= per_contrast:
                break
        words.extend(taken[:per_contrast])
    return sorted(words)


# ---------------------------------------------------------------- Azure


class Azure:
    def __init__(self, key, region, fmt):
        self.key = key
        self.region = region
        self.fmt = fmt
        self.url = "https://%s.tts.speech.microsoft.com/cognitiveservices/v1" % region

    def _headers(self):
        return {
            "Ocp-Apim-Subscription-Key": self.key,
            "Content-Type": "application/ssml+xml",
            "X-Microsoft-OutputFormat": self.fmt,
            "User-Agent": "minimal-pairs",
        }

    def synthesize(self, voice, word):
        """Return (data, error). Retries 429/5xx/network errors with backoff."""
        ssml = (
            '<speak version="1.0" xmlns="http://www.w3.org/2001/10/synthesis" '
            'xml:lang="en-GB"><voice name="%s">%s</voice></speak>' % (voice, word)
        )
        body = ssml.encode("utf-8")
        last = "no attempt"
        for attempt in range(1, ATTEMPTS + 1):
            req = urllib.request.Request(self.url, data=body, headers=self._headers(), method="POST")
            wait = 2.0 * attempt
            try:
                with urllib.request.urlopen(req, timeout=60) as r:
                    data = r.read()
                err = validate_clip(data)
                if err is None:
                    return data, None
                last = err
            except urllib.error.HTTPError as e:
                last = "HTTP %d" % e.code
                if e.code == 429 or e.code >= 500:
                    ra = e.headers.get("Retry-After")
                    if ra:
                        try:
                            wait = max(wait, float(ra))
                        except ValueError:
                            pass
                else:
                    return None, last  # 4xx other than 429: no point retrying
            except (urllib.error.URLError, OSError, ValueError) as e:
                last = "%s: %s" % (type(e).__name__, e)
            if attempt < ATTEMPTS:
                time.sleep(wait)
        return None, last

    def voices_ok(self, voices):
        """Fetch the voice list once; return the names missing or not en-GB."""
        url = "https://%s.tts.speech.microsoft.com/cognitiveservices/voices/list" % self.region
        req = urllib.request.Request(
            url, headers={"Ocp-Apim-Subscription-Key": self.key, "User-Agent": "minimal-pairs"}
        )
        with urllib.request.urlopen(req, timeout=60) as r:
            listing = json.load(r)
        locales = {v.get("ShortName"): v.get("Locale") for v in listing}
        return [v for v in voices if locales.get(v) != "en-GB"]


def validate_clip(data):
    if not data:
        return "empty response"
    if not data.startswith(EBML_MAGIC):
        return "not a WebM file (bad magic %s)" % data[:4].hex()
    if len(data) < MIN_CLIP_BYTES:
        return "too short (%d bytes)" % len(data)
    return None


# ---------------------------------------------------------------- pack


def clip_path(out, voice, word):
    return os.path.join(out, voice, word + ".webm")


def has_clip(out, voice, word):
    p = clip_path(out, voice, word)
    try:
        return os.path.getsize(p) > 0
    except OSError:
        return False


def render(azure, out, voices, words, workers):
    """Render every missing (voice, word). Returns (rendered, skipped, failures list)."""
    jobs = [(v, w) for v in voices for w in words if not has_clip(out, v, w)]
    skipped = len(voices) * len(words) - len(jobs)
    log("render: %d clips to fetch, %d already present, %d workers" % (len(jobs), skipped, workers))
    for v in voices:
        os.makedirs(os.path.join(out, v), exist_ok=True)
    failures = []
    suspicious = []
    lock = threading.Lock()
    done = [0]
    nbytes = [0]
    t0 = time.time()
    last_report = [t0]

    def one(job):
        voice, word = job
        data, err = azure.synthesize(voice, word)
        if err is not None:
            with lock:
                failures.append((voice, word, err))
                log("FAILED %s/%s: %s" % (voice, word, err))
            return
        path = clip_path(out, voice, word)
        tmp = path + ".part"
        with open(tmp, "wb") as f:
            f.write(data)
        os.replace(tmp, path)
        with lock:
            done[0] += 1
            nbytes[0] += len(data)
            if len(data) > SUSPICIOUS_CLIP_BYTES:
                suspicious.append((voice, word, len(data)))
                log("suspicious size %s/%s: %d bytes" % (voice, word, len(data)))
            now = time.time()
            if done[0] % 250 == 0 or now - last_report[0] >= 30:
                last_report[0] = now
                el = now - t0
                log(
                    "  %d/%d clips, %.1f clips/s, %.1f MB, %d failed, %ds elapsed"
                    % (done[0], len(jobs), done[0] / el if el else 0, nbytes[0] / 1e6, len(failures), el)
                )

    if jobs:
        with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as ex:
            list(ex.map(one, jobs))
    el = time.time() - t0
    log(
        "render: %d fetched (%.1f MB), %d skipped, %d failed, %d suspicious, %.0fs"
        % (done[0], nbytes[0] / 1e6, skipped, len(failures), len(suspicious), el)
    )
    return done[0], skipped, failures


def scan_pack(directory, voices):
    """{voice: {word: size}} for the clips present on disk for these voices."""
    found = {}
    for v in voices:
        d = os.path.join(directory, v)
        found[v] = {}
        if not os.path.isdir(d):
            continue
        for name in os.listdir(d):
            if not name.endswith(".webm"):
                continue
            size = os.path.getsize(os.path.join(d, name))
            if size > 0:
                found[v][name[:-5]] = size
    return found


def build_index(directory, catalog, voices, fmt):
    """index.json content for the clips present under directory."""
    found = scan_pack(directory, voices)
    # A word counts as present only when every voice has it.
    words = sorted(set.intersection(*(set(found[v]) for v in voices))) if voices else []
    present = set(words)
    by_contrast = trainable_words_by_contrast(catalog)
    covered = [c["id"] for c in catalog["contrasts"] if c["id"] in by_contrast and set(by_contrast[c["id"]]) <= present]
    all_words = set()
    for ws in by_contrast.values():
        all_words.update(ws)
    complete = bool(voices) and all_words <= present
    files = sum(len(found[v]) for v in voices)
    nbytes = sum(sum(found[v].values()) for v in voices)
    return {
        "version": 1,
        "catalog_version": catalog["version"],
        "format": FORMAT_LABELS.get(fmt, fmt),
        "voices": list(voices),
        "words": words,
        "complete": complete,
        "contrasts": covered,
        "files": files,
        "bytes": nbytes,
    }, found


def write_index_and_manifest(directory, catalog, voices, fmt):
    index, found = build_index(directory, catalog, voices, fmt)
    with open(os.path.join(directory, "index.json"), "w", encoding="utf-8") as f:
        json.dump(index, f, indent=1, ensure_ascii=False)
        f.write("\n")
    lines = []
    for v in voices:
        for w in sorted(found[v]):
            h = hashlib.sha256()
            with open(clip_path(directory, v, w), "rb") as f:
                h.update(f.read())
            lines.append("%s  %s/%s.webm\n" % (h.hexdigest(), v, w))
    with open(os.path.join(directory, "sha256.txt"), "w", encoding="utf-8") as f:
        f.writelines(lines)
    return index, found


def print_summary(label, index, found):
    log("%s: %d clips, %d words, %d bytes = %.1f MB, complete=%s, contrasts=%s"
        % (label, index["files"], len(index["words"]), index["bytes"], index["bytes"] / 1e6,
           index["complete"], ",".join(index["contrasts"]) or "-"))
    for v in index["voices"]:
        log("  %-22s %6d clips %7.2f MB" % (v, len(found[v]), sum(found[v].values()) / 1e6))


def install_assets(src, catalog, voices, fmt, words):
    """Copy the clips of `words` (all voices) into the app assets, replacing what is there."""
    dst = ASSETS_DIR
    if os.path.isdir(dst):
        shutil.rmtree(dst)
    os.makedirs(dst)
    copied = 0
    for v in voices:
        os.makedirs(os.path.join(dst, v))
        for w in words:
            p = clip_path(src, v, w)
            if os.path.isfile(p) and os.path.getsize(p) > 0:
                shutil.copyfile(p, clip_path(dst, v, w))
                copied += 1
    index, found = write_index_and_manifest(dst, catalog, voices, fmt)
    print_summary("assets %s" % dst, index, found)
    return index


def write_zip(path, src, voices, found):
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as z:
        z.write(os.path.join(src, "index.json"), "index.json")
        if os.path.isfile(os.path.join(src, "sha256.txt")):
            z.write(os.path.join(src, "sha256.txt"), "sha256.txt")
        for v in voices:
            for w in sorted(found[v]):
                # Opus does not deflate; store it as is.
                z.write(clip_path(src, v, w), "%s/%s.webm" % (v, w), compress_type=zipfile.ZIP_STORED)
    log("zip: %s, %.1f MB" % (path, os.path.getsize(path) / 1e6))


# ---------------------------------------------------------------- main


def parse_args(argv):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--catalog", default=os.path.join("data", "catalog", "catalog.json"))
    ap.add_argument("--out", default="clips")
    ap.add_argument("--voices", help="comma-separated voice ids (default: catalog voices or the six of DESIGN.md)")
    ap.add_argument("--format", default=DEFAULT_FORMAT)
    ap.add_argument("--workers", type=int, default=8)
    ap.add_argument("--limit", type=int, help="render only the first N words (tests)")
    ap.add_argument("--words", help="comma-separated subset of words")
    ap.add_argument("--contrasts", help="comma-separated subset of contrast ids")
    ap.add_argument("--install-assets", action="store_true",
                    help="copy the pack (or the --contrasts/--words subset) to %s" % ASSETS_DIR)
    ap.add_argument("--zip", metavar="PATH", help="write the pack zip (index.json at the root)")
    ap.add_argument("--placeholder", action="store_true",
                    help="render the small placeholder pack into %s" % PLACEHOLDER_DIR)
    ap.add_argument("--dry-run", action="store_true", help="list what would be rendered, no requests")
    return ap.parse_args(argv)


def main(argv=None):
    args = parse_args(argv)
    catalog = load_catalog(args.catalog)

    if args.placeholder:
        out = PLACEHOLDER_DIR
        voices = PLACEHOLDER_VOICES
        words = placeholder_words(catalog)
    else:
        out = args.out
        if args.voices:
            voices = [v.strip() for v in args.voices.split(",") if v.strip()]
        else:
            voices = list(catalog.get("voices") or DEFAULT_VOICES)
        contrasts = [c.strip() for c in args.contrasts.split(",")] if args.contrasts else None
        subset = [w.strip() for w in args.words.split(",")] if args.words else None
        words = select_words(catalog, contrasts, subset, args.limit)

    total = len(words) * len(voices)
    missing = sum(1 for v in voices for w in words if not has_clip(out, v, w))
    log("catalog %s: %d words x %d voices = %d clips into %s (%d missing)"
        % (catalog["version"], len(words), len(voices), total, out, missing))
    if args.dry_run:
        for w in words:
            log("  " + w)
        return 0

    key = os.environ.get("AZURE_SPEECH_KEY")
    region = os.environ.get("AZURE_SPEECH_REGION")
    if missing and not (key and region):
        log("AZURE_SPEECH_KEY and AZURE_SPEECH_REGION must be set in the environment")
        return 2

    t0 = time.time()
    failures = []
    if missing:
        azure = Azure(key, region, args.format)
        try:
            bad = azure.voices_ok(voices)
        except (urllib.error.URLError, OSError, ValueError) as e:
            log("could not fetch the Azure voice list: %s" % type(e).__name__)
            return 1
        if bad:
            log("voices missing from Azure or not en-GB: %s" % ", ".join(bad))
            return 1
        log("voice check: all %d voices exist with Locale en-GB" % len(voices))
        _, _, failures = render(azure, out, voices, words, args.workers)
    else:
        log("nothing to render")

    os.makedirs(out, exist_ok=True)
    index, found = write_index_and_manifest(out, catalog, voices, args.format)
    print_summary("pack %s" % out, index, found)

    if failures:
        log("FAILED after %d attempts (%d):" % (ATTEMPTS, len(failures)))
        for v, w, err in failures:
            log("  %s/%s: %s" % (v, w, err))
        return 1

    if args.install_assets and not args.placeholder:
        install_assets(out, catalog, voices, args.format, words)
    if args.zip and not args.placeholder:
        write_zip(args.zip, out, voices, found)

    log("elapsed %.0fs" % (time.time() - t0))
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except BrokenPipeError:
        sys.exit(0)
