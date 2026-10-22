#!/usr/bin/env bash
# Coverage over the JVM-tested modules.
#
# Three ways to get a wrong answer with no error, and what stops each (#80):
#
#   1. sbt 2's disk cache restores compiled classes without running the compiler, and the
#      scoverage metadata (`scoverage.coverage`) is a compile side effect, not a tracked
#      output: a cache hit gives instrumented classes with no metadata, and that module
#      drops out of the report. A fresh `-Dthicket.coverageRun` goes into the compile's
#      options (build.sbt, `coverageSettings`), so no cache entry matches and every run
#      compiles. Nothing is deleted outside this repo.
#   2. A Scala 3 coverage compile deletes the module's measurement files, and the runtime
#      records each statement once per JVM. Anything that recompiles a module after its
#      tests ran in the same sbt invocation leaves it at ~0% — a BuildInfo timestamp did.
#      `coverageMinimumStmtPerPackage` (build.sbt, `coverageGate`) fails the run.
#   3. With no metadata at all the report reads 100% of 0 statements. The check below
#      fails on that, and on any of the expected packages being absent.
set -euo pipefail

cd "$(dirname "$0")/.."

MODULES=(signalsJVM coreJVM effectZioJVM shimGen)
PACKAGES=(thicket.signals thicket.core thicket.effect.zio thicket.tools.shim)
SV=$(sbt --error "print signalsJVM/scalaVersion" 2>/dev/null | tr -d '\r' | sed $'s/\x1b\\[[0-9;]*[A-Za-z]//g' | grep -E '^[0-9]' | tail -1)
REPORT=target/out/jvm/scala-$SV/thicket/scoverage-report/scoverage.xml
rm -f "$REPORT"

tasks="coverage"
for m in "${MODULES[@]}"; do tasks="$tasks; $m/testOnly *"; done
tasks="$tasks; coverageAggregate"

echo "== running =="
status=0
sbt -Dthicket.coverageRun="$(date +%s%N)" --error "$tasks" || status=$?

echo "== verifying the report is real =="
python3 - "$REPORT" "${PACKAGES[@]}" <<'PY' || status=1
import os, sys, xml.etree.ElementTree as ET
path, expected = sys.argv[1], sys.argv[2:]
if not os.path.exists(path):
    sys.exit(f"FAIL: no report at {path}")
root = ET.parse(path).getroot()
total = int(root.get("statement-count"))
if total == 0:
    sys.exit("FAIL: the report covers 0 statements. Every module lost its scoverage "
             "metadata. The percentage is meaningless; do not trust it.")
print(f"statements {root.get('statements-invoked')}/{total}  "
      f"statement {root.get('statement-rate')}%  branch {root.get('branch-rate')}%")
found = {p.get("name") for p in root.iter("package")}
for p in sorted(root.iter("package"), key=lambda x: float(x.get("statement-rate"))):
    print(f"  {float(p.get('statement-rate')):6.2f}%  {p.get('name')}")
missing = [p for p in expected if p not in found]
if missing:
    sys.exit(f"FAIL: no coverage data for {', '.join(missing)}: the module lost its metadata.")
PY

[ "$status" -eq 0 ] && echo "== coverage OK ==" || echo "== coverage FAILED (exit $status) =="
exit "$status"
