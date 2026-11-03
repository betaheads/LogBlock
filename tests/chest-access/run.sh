#!/usr/bin/env bash
# End-to-end test of chest access logging, see tests/README.md.
# Usage (from anywhere, Git Bash on Windows): bash tests/chest-access/run.sh
# MySQL settings and what gets dropped: see tests/lib/common.sh.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
. "$HERE/../lib/common.sh"

build_logblock
compile_test "$HERE/ChestTest.java"

echo "== Building test helper plugin"
rm -rf "$WORK/classes/helper"
mkdir -p "$WORK/classes/helper"
javac -nowarn -encoding UTF-8 -cp "$(native "$ROOT/testsrv/server.jar")" -d "$(native "$WORK/classes/helper")" "$(native "$HERE/helper/src/lbtest/LBTest.java")"
cp "$HERE/helper/plugin.yml" "$WORK/classes/helper/"

prepare_server botA botB
(cd "$WORK/classes/helper" && jar cf "$(native "$SRV/plugins/LBTest.jar")" .)
printf 'logChestAccess: true\n' > "$SRV/plugins/LogBlock/world.yml"

run_test ChestTest
