#!/usr/bin/env bash
# End-to-end test of the permission groups in config.yml, see tests/README.md.
# Usage (from anywhere, Git Bash on Windows): bash tests/permissions/run.sh
# MySQL settings and what gets dropped: see tests/lib/common.sh.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
. "$HERE/../lib/common.sh"

build_logblock
compile_test "$HERE/PermissionsTest.java"
prepare_server botC
# The groups PermissionsTest expects, see the comment there
cat >> "$SRV/plugins/LogBlock/config.yml" <<'EOF'
permissions:
  groups:
    default:
      default: true
      permissions: [me]
    moderator:
      permissions: [logblock.lookup, tool, TP]
      players: [BotA]
    admin:
      ops: true
      permissions: ['*']
    typo:
      permissions: [lokup]
EOF

run_test PermissionsTest
