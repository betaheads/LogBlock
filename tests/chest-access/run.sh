#!/usr/bin/env bash
# End-to-end test of chest access logging, see tests/README.md.
# Usage (from anywhere, Git Bash on Windows): bash tests/chest-access/run.sh
# MySQL settings and what gets dropped: see tests/lib/common.sh.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
. "$HERE/../lib/common.sh"

build_logblock
compile_test "$HERE/ChestTest.java"
prepare_server botA botB
install_helper
printf 'logChestAccess: true\n' > "$SRV/plugins/LogBlock/world.yml"

run_test ChestTest
