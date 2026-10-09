#!/usr/bin/env bash
# One call, one verdict: runs the Compose UI tests of a single screen headlessly
# and prints how many of them held, plus the assertion behind every one that did not.
#
# The screen name is the package under app/src/jvmTest/kotlin/dev/ruleblend/app,
# so new screens need no change here.
#
# Usage:
#   tools/verify_ui.sh place              # one screen
#   tools/verify_ui.sh place library      # several screens in one run
#   tools/verify_ui.sh --all              # every screen
#   tools/verify_ui.sh --list             # screens the repo knows about
#   tools/verify_ui.sh place -t '*width*' # only matching test names
#
# Aliases: projects -> place, palette -> command, shell|nav -> navigation.
# Exit code: 0 all green, 1 a test failed or a screen ran no tests, 2 bad usage or a broken build,
# 3 some tests were skipped.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

TEST_ROOT="app/src/jvmTest/kotlin/dev/ruleblend/app"
RESULTS="app/build/test-results/jvmTest"
LOG="build/verify-ui.log"

screens() {
  find "$TEST_ROOT" -mindepth 1 -maxdepth 1 -type d -exec basename {} \; | sort
}

canonical() {
  case "$1" in
    projects|project|places) echo place ;;
    palette) echo command ;;
    shell|nav) echo navigation ;;
    *) echo "$1" ;;
  esac
}

NAMES=()
NAME_FILTER=""
ALL=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --all) ALL=1; shift ;;
    --list) screens | while read -r s; do
              printf '%-12s %s tests\n' "$s" "$(grep -rho '@Test' "$TEST_ROOT/$s" | wc -l | tr -d ' ')"
            done
            exit 0 ;;
    -t|--test) NAME_FILTER="$2"; shift 2 ;;
    -h|--help) sed -n '2,17p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    -*) echo "unknown argument: $1" >&2; exit 2 ;;
    *) NAMES+=("$(canonical "$1")"); shift ;;
  esac
done

if [[ $ALL -eq 1 ]]; then
  while read -r s; do NAMES+=("$s"); done < <(screens)
fi
if [[ ${#NAMES[@]} -eq 0 ]]; then
  echo "nothing to verify; pass a screen name, --all or --list" >&2
  exit 2
fi

FILTERS=()
for name in "${NAMES[@]}"; do
  if [[ ! -d "$TEST_ROOT/$name" ]]; then
    echo "unknown screen: $name (see tools/verify_ui.sh --list)" >&2
    exit 2
  fi
  FILTERS+=(--tests "dev.ruleblend.app.$name.*${NAME_FILTER}")
done

mkdir -p build
rm -rf "$RESULTS"
echo "verify_ui: ${NAMES[*]}${NAME_FILTER:+  filter=$NAME_FILTER}"

set +e
./gradlew --console=plain -q :app:jvmTest --rerun "${FILTERS[@]}" >"$LOG" 2>&1
GRADLE_STATUS=$?
set -e

if [[ ! -d "$RESULTS" ]]; then
  echo "BROKEN  no test results — gradle exited $GRADLE_STATUS"
  grep -E "^e: |error:|FAILURE:|What went wrong" -m 12 "$LOG" || tail -20 "$LOG"
  exit 2
fi

python3 - "$RESULTS" "$GRADLE_STATUS" "${NAMES[@]}" <<'PY'
import glob, os, sys, xml.etree.ElementTree as ET

results, gradle_status, names = sys.argv[1], int(sys.argv[2]), sys.argv[3:]
totals = {n: [0, 0, 0] for n in names}   # tests, failures, skipped
seconds = 0.0
detail = []

for path in sorted(glob.glob(os.path.join(results, "TEST-*.xml"))):
    suite = ET.parse(path).getroot()
    # The suite element only carries the simple name; the package lives on each case.
    cls = os.path.basename(path)[len("TEST-"):-len(".xml")]
    screen = next((n for n in names if f".app.{n}." in cls), None)
    if screen is None:
        continue
    seconds += float(suite.get("time", 0) or 0)
    for case in suite.iter("testcase"):
        totals[screen][0] += 1
        bad = case.find("failure") if case.find("failure") is not None else case.find("error")
        if bad is not None:
            totals[screen][1] += 1
            message = (bad.get("message") or bad.text or "").strip().splitlines()
            detail.append((cls.rsplit(".", 1)[-1], case.get("name", ""), message[:2]))
        elif case.find("skipped") is not None:
            totals[screen][2] += 1

failed = sum(v[1] for v in totals.values())
skipped_total = sum(v[2] for v in totals.values())
empty = [screen for screen in names if totals[screen][0] == 0]
for screen in names:
    tests, bad, skipped = totals[screen]
    if tests == 0:
        print(f"EMPTY   {screen}  0 tests ran")
        continue
    # A skipped test proves nothing, so it never counts towards the held ones.
    mark = "FAIL " if bad else "SKIP " if skipped else "PASS "
    extra = f", {skipped} skipped" if skipped else ""
    print(f"{mark}  {screen}  {tests - bad - skipped}/{tests}{extra}")

for cls, name, message in detail:
    print(f"  {cls} > {name}")
    for line in message:
        print(f"    {line}")

print(f"total   {sum(v[0] for v in totals.values())} tests, {failed} failed, {skipped_total} skipped, {seconds:.1f}s")
if failed or empty:
    sys.exit(1)
# Results written before a later build error are not a verdict.
if gradle_status != 0:
    print(f"BROKEN  gradle exited {gradle_status} after writing results")
    sys.exit(2)
sys.exit(3 if skipped_total else 0)
PY
