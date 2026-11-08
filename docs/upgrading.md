# Upgrading to 1.27

For servers that run the old LogBlock build (commit `70001ed`, version 1.26). The database tables and the world configs stay as they are. In `config.yml`, LogBlock changes `version` to `'1.27'` and adds the `permissions` section.

## Steps

1. Stop the server. Back up `plugins/LogBlock/`.
2. Replace `plugins/LogBlock.jar` with the new one. `mvn package` builds it into `testsrv/plugins/LogBlock.jar`, see the README.
3. Delete what LogBlock doesn't need any more:
   - `lib/mysql-connector-java-bin.jar`: the MySQL driver comes with Poseidon's `server.jar`;
   - `plugins/LogBlockQuestioner.jar`: confirmations are built in now;
   - `plugins/Spout.jar`, if you installed it only for LogBlock: chest access logging works without it.

   Keep `WorldEdit.jar`. LogBlock uses it for the `selection` param.
4. Start the server. The log should show `[LogBlock] Version check: 1.27` and `[LogBlock] Permissions: 3 groups in config.yml (permissions.groups).`, and no LogBlock warnings.
5. As an op, type `/lb permissions`. You should see all 10 permissions and `Your groups: default, admin`.

## What changes

- **Permissions are set in `config.yml`.** LogBlock adds the `permissions` section on the first start. Access stays the same: ops can do everything, other players can't use LogBlock. To let a player look up and roll back, type `/lb group add moderator Steve`. Details are in [permissions.md](permissions.md).
- **Permissions plugins aren't used any more.** If some ops shouldn't have every permission, take them out of `admin`: remove `ops: true` and list the admins under `players`.
- **Rollback, redo and clearlog ask for confirmation.** Type `/lb yes` or `/lb no` within 60 seconds. To turn it off, set the `questioner.ask...` options to `false`, see [confirmations.md](confirmations.md).
- **Chest access logging works without Spout.** Before, `logChestAccess: true` in `plugins/LogBlock/<world>.yml` did nothing without Spout. Now it logs. See [chest-access.md](chest-access.md).
- **`config.yml` has a comment block at the top**: a cheat sheet of the groups, commands and params. Other comments in the file are lost on every start, as before.

## Going back

Put the old `LogBlock.jar` back, along with `lib/mysql-connector-java-bin.jar` and `LogBlockQuestioner.jar` if you deleted them. The old version ignores the `permissions` section, and the database works with both versions.
