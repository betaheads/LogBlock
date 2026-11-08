# Shared part of the end-to-end tests, sourced by tests/<test>/run.sh, see tests/README.md.
# Set HERE (the test's directory) before sourcing. Sets ROOT, WORK, SRV, the MYSQL_* settings, SEP and native().
# MySQL: MYSQL_HOST (127.0.0.1), MYSQL_PORT (3317), MYSQL_DATABASE (lbtest), MYSQL_USER (root), MYSQL_PASSWORD ('').
# The tests drop and recreate the LogBlock tables of world "world" in MYSQL_DATABASE: use a test database.

LIB="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$LIB/../.." && pwd)"
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

# Builds LogBlock.jar (this runs the unit tests too) and gets the MySQL driver for the test server
build_logblock() {
	echo "== Building LogBlock"
	(cd "$ROOT" && mvn -q package)
	[ -f "$CONNECTOR" ] || (cd "$ROOT" && mvn -q dependency:get -Dartifact="mysql:mysql-connector-java:$CONNECTOR_VERSION")
}

# compile_test <java files...>: compiles them with tests/lib/*.java into $WORK/classes/test
compile_test() {
	echo "== Building the test"
	rm -rf "$WORK/classes/test"
	mkdir -p "$WORK/classes/test"
	local files=()
	for f in "$@" "$LIB"/*.java; do files+=("$(native "$f")"); done
	javac -nowarn -encoding UTF-8 -d "$(native "$WORK/classes/test")" "${files[@]}"
}

# prepare_server <ops...>: a fresh server in $SRV (ignored by git) with LogBlock and WorldEdit, offline mode on port 25599,
# the given players in ops.txt and plugins/LogBlock/config.yml with the MySQL settings. Add more to config.yml after it.
# config.yml has version 1.26, the old build: the server starts like one upgraded from it (docs/upgrading.md).
prepare_server() {
	echo "== Preparing server in $SRV"
	rm -rf "$SRV"
	mkdir -p "$SRV/plugins/LogBlock" "$SRV/lib"
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
	: > "$SRV/ops.txt"
	for op in "$@"; do echo "$op" >> "$SRV/ops.txt"; done
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
}

# install_helper: builds the test fixture plugin tests/lib/helper (/lbt, see LBTest.java) into $SRV/plugins.
# Call it after prepare_server.
install_helper() {
	echo "== Building test helper plugin"
	rm -rf "$WORK/classes/helper"
	mkdir -p "$WORK/classes/helper"
	javac -nowarn -encoding UTF-8 -cp "$(native "$ROOT/testsrv/server.jar")" -d "$(native "$WORK/classes/helper")" "$(native "$LIB/helper/src/lbtest/LBTest.java")"
	cp "$LIB/helper/plugin.yml" "$WORK/classes/helper/"
	(cd "$WORK/classes/helper" && jar cf "$(native "$SRV/plugins/LBTest.jar")" .)
}

# run_test <main class>: runs it with the args serverDir jdbcUrl user password, exits with its exit code
run_test() {
	echo "== Running test"
	local server_cp
	server_cp="$(native "$CONNECTOR")$SEP$(native "$ROOT/testsrv/server.jar")"
	java -Dtest.cp="$server_cp" -cp "$(native "$WORK/classes/test")$SEP$(native "$CONNECTOR")" "$1" \
		"$(native "$SRV")" "jdbc:mysql://$MYSQL_HOST:$MYSQL_PORT/$MYSQL_DATABASE?useSSL=false" "$MYSQL_USER" "$MYSQL_PASSWORD"
}
