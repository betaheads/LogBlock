#!/usr/bin/env bash
# Starts a throwaway MySQL 8.0 server for run.sh: data in tests/chest-access/work/mysql, 127.0.0.1:3317,
# user root without password. Runs in the foreground; stop it with Ctrl+C or
#   mysqladmin --no-defaults -h127.0.0.1 -P3317 -uroot shutdown
# MYSQLD: path to mysqld, e.g. MYSQLD="/c/Program Files/MySQL/MySQL Server 8.0/bin/mysqld" (default: mysqld from PATH).
# --no-defaults: ignores my.ini / my.cnf of an installed MySQL, so it doesn't touch that server.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
DATA="$HERE/work/mysql"
PORT="${MYSQL_PORT:-3317}"
MYSQLD="${MYSQLD:-mysqld}"

case "$(uname -s)" in
	MINGW* | MSYS* | CYGWIN*) native() { cygpath -w "$1"; } ;;
	*) native() { echo "$1"; } ;;
esac

# Poseidon's Connector/J 5.1.x can't use caching_sha2_password, the MySQL 8 default
if [ ! -d "$DATA" ]; then
	echo "== Initializing $DATA"
	mkdir -p "$(dirname "$DATA")"
	"$MYSQLD" --no-defaults --initialize-insecure --datadir="$(native "$DATA")" --default-authentication-plugin=mysql_native_password
fi
echo "== Starting mysqld on 127.0.0.1:$PORT"
exec "$MYSQLD" --no-defaults --datadir="$(native "$DATA")" --port="$PORT" --bind-address=127.0.0.1 --mysqlx=OFF \
	--default-authentication-plugin=mysql_native_password --console
