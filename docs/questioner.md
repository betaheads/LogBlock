# LogBlockQuestioner: confirmations (disabled)

## What it did

LogBlockQuestioner is a small optional plugin by DiddiZ. `questioner.ask(player, question, "yes", "no")` sends a question to the player's chat. It then blocks the command's worker thread until the player answers.

LogBlock asked these questions only when all of the following are true:
- the sender is a player, not the console;
- the command is not `silent`;
- the plugin is installed.

| Command | Question | Config option |
|---|---|---|
| `/lb rollback` | "Are you sure you want to continue?" | `questioner.askRollbacks` |
| after a rollback | "Do you want to delete the rollbacked log?" On "yes", runs clearlog for the same params. Asked only if the player has `logblock.clearlog`. | `questioner.askClearLogAfterRollback` |
| `/lb redo` | "Are you sure you want to continue?" | `questioner.askRedos` |
| `/lb clearlog` | Shows how many blocks were found, then "Are you sure you want to continue?" | `questioner.askClearLogs` |

## Why it is disabled

LogBlockQuestioner is not in any Maven repository, so the Maven build can't compile against it. Its old download URL (`cloud.github.com/downloads/DiddiZ/LogBlockQuestioner`) is dead. It is not installed on our server either.

## What is commented out

Search the code for `QUESTIONER DISABLED`:

| Where | What |
|---|---|
| `src/de/diddiz/LogBlock/CommandsHandler.java` | The import, the `questioner` field, its initialization in the constructor, and the 4 question blocks: rollback, clearlog after rollback, redo, clearlog. |
| `pom.xml` | A placeholder comment where the dependency would go. |

## Current behavior

The plugin behaves exactly as it did before without LogBlockQuestioner installed (`questioner == null`):

- rollback, redo and clearlog run immediately, without confirmation. Be careful with `/lb clearlog`.
- After a rollback, LogBlock doesn't offer to delete the rolled-back log.
- The `questioner.*` options are still read in `Config.java`, but nothing uses them.

## How to restore

1. Get `LogBlockQuestioner.jar` (the old Ant build used v0.02) and put it into `testsrv/plugins/`. It is not verified that it works on Poseidon.
2. Add it to `pom.xml` the same way as `server.jar`:
   ```xml
   <dependency>
       <groupId>de.diddiz</groupId>
       <artifactId>logblockquestioner</artifactId>
       <version>0.02</version>
       <scope>system</scope>
       <systemPath>${project.basedir}/testsrv/plugins/LogBlockQuestioner.jar</systemPath>
   </dependency>
   ```
3. Uncomment everything marked `QUESTIONER DISABLED` in `CommandsHandler.java`. `LogBlockQuestioner` is already in `softdepend` in `src/resources/plugin.yml`.

Alternative without an extra plugin: implement confirmation in LogBlock itself. For example, store the pending command in the player's `Session` and run it on a confirm subcommand such as `/lb yes`.
