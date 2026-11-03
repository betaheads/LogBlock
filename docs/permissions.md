# Permissions: groups in config.yml

Who may use which `/lb` command is set in `plugins/LogBlock/config.yml`, under `permissions.groups`. You don't need a permissions plugin.

## Quick start

On the first start LogBlock adds three groups to `config.yml`. Here they are with two moderators added:

```yaml
permissions:
    usePermissionsPlugin: false
    groups:
        default:
            default: true
            permissions: []
            players: []
        moderator:
            permissions:
            - lookup
            - tool
            - toolblock
            - tp
            - rollback
            players:
            - Steve
            - Alex
        admin:
            ops: true
            permissions:
            - '*'
            players: []
```

- Ordinary players get nothing: the `default` group has every player but gives no permissions. To give something to everyone, for example `/lb me`, add it there: `permissions: [me]`.
- Steve and Alex can do lookups, use the tool and the tool block, teleport and roll back. Rollbacks stay inside `rollback.maxTime` and `rollback.maxArea`.
- Ops can do everything.

To add a player, either:
- write the nickname under `players` and type `/lb group reload` (no restart needed), or
- type `/lb group add moderator Steve` in the server console or in game. This saves `config.yml` for you.

## Who is in a group

| Setting | Who is in the group |
|---|---|
| `default: true` | every player |
| `ops: true` | every op (`ops.txt`) |
| `players: [Steve, Alex]` | these players, by nickname. Case doesn't matter: `steve` is the same as `Steve`. |

A player can be in several groups. They get the permissions of all of them. For example, with `permissions: [me]` in `default`, Steve from the example above would have `me` plus the moderator permissions. Groups don't inherit from each other and there are no negative permissions. If several groups need the same permission, give it to `default` or list it in each group.

You can rename groups, delete them and add new ones. Only the four settings above count, the group names don't. A group called `default` without `default: true` is an ordinary group.

The console has all permissions.

## Permissions

| Permission | Allows |
|---|---|
| `me` | `/lb me` |
| `lookup` | `/lb lookup` and the short form (`/lb player Steve`), your own tool queries (`/lb tool <params>`), tool mode `lookup` |
| `tool` | using the tool (wood pickaxe, `lookup.toolID`) with the default query `lookup.toolQuery` |
| `toolblock` | using the tool block (bedrock, `lookup.toolblockID`) with the default query `lookup.toolBlockQuery` |
| `tp` | `/lb tp` |
| `hide` | `/lb hide` |
| `rollback` | `/lb rollback`, `/lb redo`, `/lb writelogfile`, `/lb savequeue`, tool modes `rollback`, `redo`, `writelogfile` |
| `clearlog` | `/lb clearlog`, tool mode `clearlog`, the question "Also delete the log of the rolled back blocks?" after a rollback |
| `ignoreRestrictions` | rollbacks beyond `rollback.maxTime` and `rollback.maxArea` |
| `groups` | `/lb group`: list groups, add and remove players, reload. **Give it to admins only:** with it a player can add themselves to any group. |
| `'*'` or `all` | all of the above |

You can write a permission as `lookup` or as `logblock.lookup`. Case doesn't matter. In YAML, `*` must be in quotes (`- '*'`), otherwise the file doesn't load. Or write `all`.

Everyone can use `/lb help`, `/lb commands`, `/lb params`, `/lb permissions`, `/lb logging`, `/lb page`, `/lb next`, `/lb prev`, `/lb yes` and `/lb no`, and can turn their tool on or off (`/lb tool on|off`).

The tool checks the permission of its mode (`/lb tool mode rollback`) on every click. If you remove `rollback` from a player, their tool stops rolling back immediately.

## Commands

| Command | Needs | What it does |
|---|---|---|
| `/lb permissions` | nothing | your permissions and your groups |
| `/lb group` | `groups` | lists the groups: who is in them and what they give |
| `/lb group add <group> <player>` | `groups` | adds the player to the group's `players`, saves `config.yml` |
| `/lb group remove <group> <player>` | `groups` | removes the player from the group's `players`, saves `config.yml` |
| `/lb group reload` | `groups` | reads the groups from `config.yml` again, after you edited it |

`add` and `remove` change only the `players` list. They can't create groups or change permissions, do that in `config.yml`. Group names aren't case sensitive. `add` reads `config.yml` from disk first, so any edits you made there and haven't reloaded yet are kept and applied too.

Each change goes to the server log, for example `[LogBlock] botC removed Steve from permission group moderator`.

## Mistakes in config.yml

- **An unknown permission or setting, or a broken group.** LogBlock writes a warning to the server log at startup and on `/lb group reload`, and shows it to whoever typed the reload. Everything else keeps working. Logging never stops because of a mistake in the permissions.
  ```
  [WARNING] [LogBlock] config.yml: Group 'moderator': unknown permission 'lokup', allowed are me, lookup, ...
  ```
- **A YAML syntax error on `/lb group reload` or `add`/`remove`.** The answer is "config.yml has a mistake (see server log), nothing changed". The old groups stay and the file isn't touched. The log shows the line and column of the error. At startup a YAML syntax error stops LogBlock, the same as for any other setting.
- **Nicknames YAML reads as something else.** `yes`, `no`, `on`, `off`, `true`, `false`, `null`, `~`, and numbers with a leading zero like `007`, need quotes: `- 'yes'`. `/lb group add` adds the quotes for you.
- **No `permissions.groups` at all**, for example if you deleted it. Only the console can use LogBlock commands. On the next start LogBlock adds the default groups again.

`config.yml` is rewritten on every start and by `/lb group add|remove`. Comments you add are lost and the order of the settings may change. The comment block at the top is written by LogBlock and stays. It is a cheat sheet: how groups work, the permissions, every `/lb` command with the permission it needs, and the query params. To change it, edit `Config.HEADER`. The unit tests check that every line starts with `#` and is at most 120 characters wide.

## Permissions plugins

With `permissions.usePermissionsPlugin: true`, LogBlock also asks a permissions plugin. A player has a permission if their groups in `config.yml` give it **or** the plugin does:
- if the old Permissions plugin (Nijikokun) is installed, LogBlock asks it;
- otherwise it asks Bukkit permissions (PermissionsBukkit, PermissionsEx and others). Without such a plugin the defaults from `plugin.yml` apply, which means ops have every permission.

The default is `false`: only `config.yml` counts.

## Upgrading from earlier versions

Before, every `/lb` command was for ops only. A permissions plugin was used if one was installed, but it couldn't give permissions to anyone who wasn't an op.

On the first start after the upgrade LogBlock adds the default groups to `config.yml`. Ops are in `admin` and have every permission, as before. Ordinary players get nothing, as before. If you used a permissions plugin, set `permissions.usePermissionsPlugin: true`.

## How it works

- `src/de/diddiz/LogBlock/PermissionGroups.java` reads `permissions.groups` (`parse`), answers "does this player have this permission" (`has`), and changes the `players` lists for `/lb group add|remove` (`addPlayer`, `removePlayer`). It is plain Java without Bukkit, so the unit tests can use it. A `PermissionGroups` object never changes: a reload builds a new one and `LogBlock` swaps the reference (`volatile`), so commands running in async tasks always see a complete set of groups.
- `LogBlock.hasPermission` checks every permission: the console has them all, then the groups, then the permissions plugin if `usePermissionsPlugin` is set. `reloadPermissionGroups` and `changeGroup` read `config.yml` from disk, and `changeGroup` saves it.
- `Config` adds the default groups (`PermissionGroups.defaults()`) only if `permissions.groups` is missing, so groups you delete don't come back. It also writes the comment block at the top (`Config.HEADER`).
- `CommandsHandler`: `/lb permissions` and `/lb group` (`groupCommand`).
- `LBToolListener` checks the permission of the tool mode on every use.

Tests:
- `tests/unit/de/diddiz/LogBlock/PermissionGroupsTest.java` runs with `mvn package`. It covers the rules above, the warnings, add and remove, and a round trip through Bukkit's YAML for the defaults and nicknames like `yes`.
- `tests/permissions` checks everything above with bots on a real server. See [tests/README.md](../tests/README.md).
