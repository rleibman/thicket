#!/usr/bin/env bash
# Coverage over the JVM-tested modules.
#
# The dance below is not ceremony. Two sbt-2 behaviours will otherwise hand you a wrong
# answer with no error:
#
#   1. sbt 2's disk cache restores compiled classes without running the compiler. The
#      scoverage metadata file (`scoverage.coverage`) is a *compile side-effect*, not a
#      tracked output, so a cache hit produces instrumented classes with no metadata. The
#      affected module then vanishes from the aggregate silently.
#   2. A module whose metadata is missing contributes zero statements. With every module
#      missing, the report reads **100% of 0 statements** and sails past
#      `coverageFailOnMinimum`. That is the failure this script exists to make impossible.
#
# So: clear the cache, run, then check the report is not empty before believing it.
set -euo pipefail

cd "$(dirname "$0")/.."

MODULES=(signalsJVM coreJVM effectZioJVM shimGen)
REPORT=target/out/jvm/scala-3.9.0/thicket/scoverage-report/scoverage.xml

echo "== clearing the sbt disk cache (see the comment above; this is required) =="
rm -rf ~/.cache/sbt/v2 target/out

tasks="coverage"
for m in "${MODULES[@]}"; do tasks="$tasks; $m/testOnly *"; done
tasks="$tasks; coverageAggregate"

echo "== running =="
sbt --error "$tasks"

echo "== verifying the report is real =="
python3 - "$REPORT" <<'PY'
import sys, xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
total = int(root.get("statement-count"))
if total == 0:
    sys.exit("FAIL: the report covers 0 statements. Every module lost its scoverage "
             "metadata — a cache hit, almost certainly. The percentage above is "
             "meaningless; do not trust it.")
print(f"statements {root.get('statements-invoked')}/{total}  "
      f"statement {root.get('statement-rate')}%  branch {root.get('branch-rate')}%")
for p in sorted(root.iter("package"), key=lambda x: float(x.get("statement-rate"))):
    print(f"  {float(p.get('statement-rate')):6.2f}%  {p.get('name')}")
PY
