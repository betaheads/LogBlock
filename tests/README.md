# Tests

- **Unit tests** in `tests/unit` (JUnit 4). They run with every `mvn package` and need no server and no MySQL.
- **End-to-end tests** in `tests/chest-access`, `tests/permissions` and `tests/fresh-install`. Each one starts a real Poseidon server and connects bots to it. They run separately from `mvn package`.

## Unit tests

```sh
mvn test        # or mvn package, which runs them before building the jar
```

`PermissionGroupsTest` checks the permission groups from [docs/permissions.md](../docs/permissions.md): who is in a group, how permissions add up, `'*'` and `all`, names with and without `logblock.`, warnings for mistakes, `/lb group add|remove` on the config map, and a round trip through Bukkit's YAML (`org.bukkit.util.config.Configuration` from `server.jar`) for the default groups and for nicknames like `yes` or `007`. It also checks that the default groups give an ordinary player nothing and an op everything, and that `plugin.yml` gives every permission to ops only (`default: op`), which matters with `usePermissionsPlugin: true` and no permissions plugin.

## End-to-end tests

### Run

Requirements: JDK 8, Maven, `testsrv/server.jar` (same as for the build), a MySQL server. On Windows use Git Bash.

```sh
# 1. MySQL. Either a throwaway MySQL 8.0 on 127.0.0.1:3317 (data in tests/work/mysql), in a second terminal:
MYSQLD="/c/Program Files/MySQL/MySQL Server 8.0/bin/mysqld" bash tests/start-mysql.sh
#    or any MySQL you have, with a database just for the tests:
export MYSQL_HOST=127.0.0.1 MYSQL_PORT=3306 MYSQL_DATABASE=lbtest MYSQL_USER=... MYSQL_PASSWORD=...

# 2. The tests, one after the other (they use the same port)
bash tests/chest-access/run.sh
bash tests/permissions/run.sh
bash tests/fresh-install/run.sh
```

Each one ends with `ALL PASSED` (exit code 0) or `FAILED` (exit code 1). Each takes about a minute, mostly for world generation.

**The tests drop the LogBlock tables of world `world` (`lb-players`, `lb-world`, `lb-world-sign`, `lb-world-chest`) in `MYSQL_DATABASE` before they start.** Never point them at a production database. The database is created if it doesn't exist.

### Shared parts: tests/lib

- `common.sh`, sourced by every `run.sh`: builds LogBlock (`mvn package`, which runs the unit tests too), compiles the test with `tests/lib/*.java`, and prepares a server in `tests/<test>/work/srv/` (ignored by git). The server gets `LogBlock.jar`, WorldEdit in `lib/`, `server.properties` (offline mode, port 25599), `ops.txt` and a LogBlock `config.yml` with the MySQL settings. Then it runs the test.
- `TestServer.java` starts the server, types commands into its console and waits for its output. The full server log is in `work/srv/test-server-output.log`.
- `Bot.java` is a network client that speaks the b1.7.3 protocol (version 14). It logs in, types chat and commands (`say`), reads the chat answers (`expect`, `collect`, `expectNothing`, `expectPermissions` for `/lb permissions`), right-clicks blocks with the item in hotbar slot 0 (`place`), opens containers and clicks slots.
- `Steps.java`: the tests extend it. `step` runs one step; a failed step is counted and printed, and the test goes on with the next one. `finish` prints `ALL PASSED` or `FAILED` and sets the exit code.
- `helper/`: the test fixture plugin `LBTest`. `install_helper` in `common.sh` builds it into the test server. Typed into the server console: `/lbt setup <players...>` builds a stone platform at y=99 around 0,0 with containers and teleports the players onto it, `/lbt air x y z` removes a block, `/lbt give <player> <id>` puts an item into hotbar slot 0. It changes blocks without events, so LogBlock logs nothing for them. Never install it on a real server.

Notes:
- `common.sh` puts Connector/J 5.1.49 in front of `server.jar` on the test server's classpath, because the 5.1.14 driver bundled in Poseidon can't connect to MySQL 8. On the real server nothing changes.
- The server wraps chat lines that are wider than the chat window into several messages. Tests check only the start of long answers.
- The bot sends clicks without the item the client would expect. The server rejects such a click but still applies it, and the bot confirms the rejection with packet 0x6A like a real client. Without that confirmation the server ignores every following click.

## chest-access: chest access logging

Checks `LBChestAccessListener` (see [docs/chest-access.md](../docs/chest-access.md)). `run.sh` also installs the helper plugin (`tests/lib/helper`) and turns on `logChestAccess: true` for `world`. Both bots are in `ops.txt`.

`ChestTest` connects two bots, `botA` and `botB`. The helper plugin (`/lbt setup`, typed into the server console) builds the scene: a stone platform near 0,0 at y=100 with a chest, a double chest, a chest with a block above it (it can't open) and a dispenser. Then the bots open containers and move items:

| Step | What happens | Expected log entries |
|---|---|---|
| T1 | botA opens a chest, takes 10 stone, puts in 20 glass | stone -10, glass +20 |
| T2 | botA opens a double chest, takes items from both halves | cobblestone -7 at one half, planks -9 at the other |
| T3 | botA right-clicks the chest that can't open | nothing |
| T4 | botA and botB open the same chest; botA takes dirt and closes, then botB takes glass | botA dirt -5, botB glass -20 (only its own change) |
| T5 | botB opens the chest, puts glass back and disconnects without closing | botB glass +20 |
| T6 | botA puts 10 stone into the dispenser | stone +10 |
| T7 | botA opens the chest, the chest is removed (`/lbt air`) | nothing |

After `stop` the test reads `lb-world` + `lb-players` + `lb-world-chest` and compares them with the expected entries in order.

Changing the scenario: the scene is in `tests/lib/helper/src/lbtest/LBTest.java`, the steps and expected entries are in `ChestTest.main`. Window slot numbers are listed in a comment there.

## permissions: permission groups in config.yml

Checks the groups from [docs/permissions.md](../docs/permissions.md). `run.sh` adds these groups to `config.yml`; only `botC` is in `ops.txt`:

```yaml
permissions:
  groups:
    default:   {default: true, permissions: [me]}
    moderator: {permissions: [logblock.lookup, tool, TP], players: [BotA]}
    admin:     {ops: true, permissions: ['*']}
    typo:      {permissions: [lokup]}
```

`PermissionsTest` connects `botA`, `botB` and `botC`. They type `/lb` commands into the chat, and the test checks the answers:

| Step | What happens | Expected |
|---|---|---|
| P1 | server start | warning about the unknown permission `lokup`, LogBlock works |
| P2 | botA (`BotA` in config.yml): `/lb permissions` | `me, lookup, tool, tp`, groups `default, moderator` |
| P3–P5 | botA: `/lb lookup area 5`, `/lb rollback area 5`, `/lb tool mode rollback` | lookup runs; rollback and the rollback tool mode are denied |
| P6 | botB (only `default`): `/lb permissions`, `/lb me`, `/lb lookup area 5` | only `me`; `/lb me` runs, lookup denied |
| P7 | botB: `/lb group add moderator botB`, `/lb group` | denied |
| P8 | botC (op): `/lb permissions` | all 10 permissions, groups `default, admin` |
| P9 | botC: `/lb group` | the list of groups with their permissions and players |
| P10 | console: `lb group add moderator botB` | saved in config.yml, botB's lookup runs |
| P11 | botC: `/lb group remove moderator BOTB` | removed from config.yml, botB's lookup denied again |
| P12 | botC: unknown group, already in the group, not in the group, missing argument | error messages |
| P13 | the test rewrites `permissions` in config.yml by hand, console: `lb group reload` | new groups apply: botB's lookup runs, his `/lb me` and botA's lookup are denied |
| P14 | the test breaks the YAML, console: `lb group reload` and `lb group add` | "config.yml has a mistake", the file isn't changed, the old groups stay |

A failed step is reported and the test goes on with the next one. Changing the scenario: the groups are in `run.sh`, the steps in `PermissionsTest.main`.

## fresh-install: only ops may use LogBlock

Checks what happens if you just install the plugin: `config.yml` has only the MySQL settings and `version: '1.26'` from `common.sh`, no `permissions` section. That is also what a server upgraded from the old build has (see [docs/upgrading.md](../docs/upgrading.md)). LogBlock has to add the default groups (see [docs/permissions.md](../docs/permissions.md)) and with them give everything to ops and nothing to anyone else. `run.sh` installs the helper plugin; only `botB` is in `ops.txt`.

`FreshInstallTest` connects `botA` (an ordinary player) and `botB` (an op). `/lbt setup` puts them on the stone platform, so they have blocks to click:

| Step | What happens | Expected |
|---|---|---|
| F1 | server start, console: `lb group reload` | no warnings about `config.yml`, `usePermissionsPlugin: false` in it, "Reloaded 3 groups" from the file |
| F2 | botB: `/lb group` | `default` (every player, no permissions), `moderator` (lookup, tool, toolblock, tp, rollback), `admin` (ops, `*`) |
| F3 | botA: `/lb permissions` | `none`, groups `default` |
| F4 | botA: every command that needs a permission, aliases (`/lb t`, `/lb rb`, ...) and the short lookup form (`/lb player botB`) included | each one denied |
| F5 | botA: `/lb tool mode` and `/lb toolblock mode` with each of the 5 modes | each one denied |
| F6 | botA: the commands for everyone: `/lb`, `help`, `commands`, `params`, `permissions`, `logging`, `page`, `next`, `prev`, `yes`, `no`, `tool on/off`, `toolblock on/off` | each one answers |
| F7 | botB: `/lb permissions`, `/lb lookup area 5`, `/lb me`, `/lb tool` and `/lb toolblock`, then a right click with each | all 10 permissions, groups `default, admin`; lookups run, both tools answer |
| F8 | botA gets a wood pickaxe and bedrock (`/lbt give`), right-clicks a block with each | no answer: without the permission the tool does nothing, even with tool on |
| F9 | console: `lb group add moderator botA`, botA clicks again, then `/lb clearlog area 5`, `/lb group` | botA in config.yml; both tools answer now, so the silence in F8 came from the permissions; clearlog and group still denied |

Changing the scenario: the command lists are at the top of `FreshInstallTest`, the steps in `FreshInstallTest.main`. If you add a `/lb` command, add it to `NEED_PERMISSION` or `FOR_EVERYONE` there.
