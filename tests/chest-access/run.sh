#!/usr/bin/env bash
# End-to-end test of chest access logging, see tests/README.md.
# Usage (from anywhere, Git Bash on Windows): bash tests/chest-access/run.sh
# MySQL: MYSQL_HOST (127.0.0.1), MYSQL_PORT (3317), MYSQL_DATABASE (lbtest), MYSQL_USER (root), MYSQL_PASSWORD ('').
# The test drops and recreates the LogBlock tables of world "world" in MYSQL_DATABASE: use a test database.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
WORK="$HERE/work"
SRV="$WORK/srv"
MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-3317}"
MYSQL_DATABASE="${MYSQL_DATABASE:-lbtest}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-}"
M2="${M2_REPO:-$HOME/.m2/repository}"
# Newer than the Connector/J 5.1.14 bundled in Poseidon's server.jar, which can't talk to MySQL 8.
# Put first on the test server's classpath; the plugin itself is unchanged.
CONNECTOR_VERSION=5.1.49
CONNECTOR="$M2/mysql/mysql-connector-java/$CONNECTOR_VERSION/mysql-connector-java-$CONNECTOR_VERSION.jar"
WORLDEDIT="$M2/com/sk89q/worldedit/4.6/worldedit-4.6.jar"

case "$(uname -s)" in
	MINGW* | MSYS* | CYGWIN*) SEP=';'; native() { cygpath -w "$1"; } ;;
	*) SEP=':'; native() { echo "$1"; } ;;
esac

if [ ! -f "$ROOT/testsrv/server.jar" ]; then
	echo "testsrv/server.jar not found: put Poseidon's server.jar there (see README)" >&2
	exit 1
fi

echo "== Building LogBlock"
(cd "$ROOT" && mvn -q package)
[ -f "$CONNECTOR" ] || (cd "$ROOT" && mvn -q dependency:get -Dartifact="mysql:mysql-connector-java:$CONNECTOR_VERSION")

echo "== Building test helper plugin and bot"
rm -rf "$WORK/classes" "$SRV"
mkdir -p "$WORK/classes/helper" "$WORK/classes/bot" "$SRV/plugins/LogBlock" "$SRV/lib"
javac -nowarn -encoding UTF-8 -cp "$(native "$ROOT/testsrv/server.jar")" -d "$(native "$WORK/classes/helper")" "$(native "$HERE/helper/src/lbtest/LBTest.java")"
cp "$HERE/helper/plugin.yml" "$WORK/classes/helper/"
(cd "$WORK/classes/helper" && jar cf "$(native "$SRV/plugins/LBTest.jar")" .)
javac -nowarn -encoding UTF-8 -d "$(native "$WORK/classes/bot")" "$(native "$HERE/ChestTest.java")"

echo "== Preparing server in $SRV"
cp "$ROOT/testsrv/plugins/LogBlock.jar" "$SRV/plugins/"
# LogBlock.jar's manifest has Class-Path: ../lib/WorldEdit.jar
cp "$WORLDEDIT" "$SRV/lib/WorldEdit.jar"
cat > "$SRV/server.properties" <<EOF
online-mode=false
server-ip=127.0.0.1
server-port=25599
level-name=world
level-seed=12345
spawn-monsters=false
spawn-animals=false
allow-flight=true
max-players=5
view-distance=3
EOF
printf 'botA\nbotB\n' > "$SRV/ops.txt"
touch "$SRV/banned-players.txt" "$SRV/banned-ips.txt" "$SRV/white-list.txt"
cat > "$SRV/plugins/LogBlock/config.yml" <<EOF
version: '1.26'
loggedWorlds:
- world
mysql:
  host: $MYSQL_HOST
  port: $MYSQL_PORT
  database: $MYSQL_DATABASE
  user: $MYSQL_USER
  password: '$MYSQL_PASSWORD'
consumer:
  delayBetweenRuns: 1
EOF
printf 'logChestAccess: true\n' > "$SRV/plugins/LogBlock/world.yml"

echo "== Running test"
SERVER_CP="$(native "$CONNECTOR")$SEP$(native "$ROOT/testsrv/server.jar")"
java -Dtest.cp="$SERVER_CP" -cp "$(native "$WORK/classes/bot")$SEP$(native "$CONNECTOR")" ChestTest \
	"$(native "$SRV")" "jdbc:mysql://$MYSQL_HOST:$MYSQL_PORT/$MYSQL_DATABASE?useSSL=false" "$MYSQL_USER" "$MYSQL_PASSWORD"
