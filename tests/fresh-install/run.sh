#!/usr/bin/env bash
# End-to-end test of a fresh install: only ops may use LogBlock, see tests/README.md.
# Usage (from anywhere, Git Bash on Windows): bash tests/fresh-install/run.sh
# MySQL settings and what gets dropped: see tests/lib/common.sh.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
. "$HERE/../lib/common.sh"

build_logblock
compile_test "$HERE/FreshInstallTest.java"
# config.yml stays as prepare_server writes it: the MySQL settings only, no permissions
prepare_server botB
install_helper

run_test FreshInstallTest
