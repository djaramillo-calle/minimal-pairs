#!/usr/bin/env bash
# Repository check: Python selftests, catalog check, contract examples, Android unit tests.
#
# Usage:  scripts/check.sh            run everything
#         SKIP_GRADLE=1 scripts/check.sh   skip the Android unit tests
#
# Scripts that do not exist yet are skipped with a message, so this is usable
# while the tree is still being built. Any failure aborts with a non-zero exit.
set -euo pipefail

cd "$(dirname "$0")/.."

step() { printf '\n==> %s\n' "$*"; }

run_selftest() {
    local script="$1"
    if [ -f "$script" ]; then
        step "$script --selftest"
        python3 "$script" --selftest
    else
        step "skip: $script not present"
    fi
}

run_selftest scripts/build-catalog.py
if [ -f scripts/build-catalog.py ] && [ -f data/catalog/catalog.json ]; then
    step "scripts/build-catalog.py --check"
    python3 scripts/build-catalog.py --check
else
    step "skip: build-catalog.py --check (no data/catalog/catalog.json)"
fi

run_selftest scripts/plan-from-ledger.py
run_selftest scripts/sessions-summary.py
run_selftest scripts/progress-report.py
run_selftest scripts/validate-contract.py

if [ -f scripts/validate-contract.py ] && [ -d data/examples ]; then
    step "scripts/validate-contract.py data/examples"
    if [ -f data/catalog/catalog.json ]; then
        python3 scripts/validate-contract.py data/examples --catalog data/catalog/catalog.json
    else
        python3 scripts/validate-contract.py data/examples
    fi
else
    step "skip: validate-contract.py data/examples (script or folder missing)"
fi

if [ -f scripts/validate-contract.py ] && [ -f docs/CONTRACT.md ]; then
    step "scripts/validate-contract.py on the worked examples of docs/CONTRACT.md"
    tmp="$(mktemp -d)"
    mkdir -p "$tmp/sessions"
    python3 - "$tmp" <<'DOCJSON'
import json, os, re, sys
out = sys.argv[1]
blocks = re.findall(r"```json\n(.*?)```", open("docs/CONTRACT.md", encoding="utf-8").read(), re.S)
for text in blocks:
    obj = json.loads(text)                      # a malformed example fails here
    if "weights" in obj:
        name = "plan.json"
    elif "contrasts" in obj and "words" in obj:
        name = "state.json"
    else:
        name = os.path.join("sessions", obj["id"] + ".json")
    with open(os.path.join(out, name), "w", encoding="utf-8") as f:
        json.dump(obj, f)
with open(os.path.join(out, "catalog-version.txt"), "w", encoding="utf-8") as f:
    f.write("2026-09-11.2\n")
print("extracted %d worked example(s)" % len(blocks))
DOCJSON
    if [ -f data/catalog/catalog.json ]; then
        python3 scripts/validate-contract.py "$tmp" --catalog data/catalog/catalog.json
    else
        python3 scripts/validate-contract.py "$tmp"
    fi
    rm -rf "$tmp"
fi

if [ -f scripts/progress-report.py ] && [ -d data/examples ]; then
    step "scripts/progress-report.py data/examples (report and plan to a temp dir)"
    tmp="$(mktemp -d)"
    python3 scripts/progress-report.py data/examples --out "$tmp/report.md" --plan-out "$tmp/plan.json" >/dev/null
    python3 scripts/validate-contract.py "$tmp" --quiet
    rm -rf "$tmp"
fi

if [ "${SKIP_GRADLE:-0}" = "1" ]; then
    step "skip: Android unit tests (SKIP_GRADLE=1)"
elif [ -x ./gradlew ]; then
    step "./gradlew --no-daemon -q :app:testDebugUnitTest"
    ./gradlew --no-daemon -q :app:testDebugUnitTest
else
    step "skip: Android unit tests (no ./gradlew)"
fi

printf '\nPASS: all checks succeeded\n'
