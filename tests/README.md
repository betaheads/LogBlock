# Tests

## chest-access: end-to-end test of chest access logging

Checks `LBChestAccessListener` (see [docs/chest-access.md](../docs/chest-access.md)) on a real Poseidon server. It runs outside of `mvn package`.

### Run

Requirements: JDK 8, Maven, `testsrv/server.jar` (same as for the build), a MySQL server. On Windows use Git Bash.

```sh
# 1. MySQL. Either a throwaway MySQL 8.0 on 127.0.0.1:3317 (data in tests/chest-access/work/mysql), in a second terminal:
MYSQLD="/c/Program Files/MySQL/MySQL Server 8.0/bin/mysqld" bash tests/chest-access/start-mysql.sh
#    or any MySQL you have, with a database just for the test:
export MYSQL_HOST=127.0.0.1 MYSQL_PORT=3306 MYSQL_DATABASE=lbtest MYSQL_USER=... MYSQL_PASSWORD=...

# 2. The test
bash tests/chest-access/run.sh
```

It ends with `ALL PASSED` (exit code 0) or `FAILED` (exit code 1). It takes about a minute, mostly world generation on the first start.

**The test drops the LogBlock tables of world `world` (`lb-players`, `lb-world`, `lb-world-sign`, `lb-world-chest`) in `MYSQL_DATABASE` before it starts.** Never point it at a production database. The database is created if it doesn't exist.

### What it does

`run.sh`:
1. builds LogBlock (`mvn package`), the helper plugin `helper/` and the bot `ChestTest.java`;
2. prepares a server in `tests/chest-access/work/srv/` (ignored by git): `LogBlock.jar`, `LBTest.jar`, WorldEdit in `lib/`, `server.properties` (offline mode, port 25599), both bots in `ops.txt`, LogBlock config with `logChestAccess: true` for `world`;
3. runs `ChestTest`, which starts the server, connects the bots and checks the database.

`ChestTest` connects two bots, `botA` and `botB`, to the server. They are network clients that speak the b1.7.3 protocol (version 14). The helper plugin (`/lbt setup`, typed into the server console) builds the scene: a stone platform near 0,0 at y=100 with a chest, a double chest, a chest with a block above it (it can't open) and a dispenser. Then the bots open containers and move items:

| Step | What happens | Expected log entries |
|---|---|---|
| T1 | botA opens a chest, takes 10 stone, puts in 20 glass | stone -10, glass +20 |
| T2 | botA opens a double chest, takes items from both halves | cobblestone -7 at one half, planks -9 at the other |
| T3 | botA right-clicks the chest that can't open | nothing |
| T4 | botA and botB open the same chest; botA takes dirt and closes, then botB takes glass | botA dirt -5, botB glass -20 (only its own change) |
| T5 | botB opens the chest, puts glass back and disconnects without closing | botB glass +20 |
| T6 | botA puts 10 stone into the dispenser | stone +10 |
| T7 | botA opens the chest, the chest is removed (`/lbt air`) | nothing |

After `stop` the test reads `lb-world` + `lb-players` + `lb-world-chest` and compares them with the expected entries in order. The full server log is in `work/srv/test-server-output.log`.

Changing the scenario: the scene is in `helper/src/lbtest/LBTest.java`, the steps and expected entries are in `ChestTest.main`. Window slot numbers are listed in a comment there.

### Notes

- `run.sh` puts Connector/J 5.1.49 in front of `server.jar` on the test server's classpath, because the 5.1.14 driver bundled in Poseidon can't connect to MySQL 8. On the real server nothing changes.
- The bot sends clicks without the item the client would expect. The server rejects such a click but still applies it, and the bot confirms the rejection with packet 0x6A like a real client. Without that confirmation the server ignores every following click.
