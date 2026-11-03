# Confirmations: /lb yes and /lb no

Rollback, redo and clearlog ask the player to confirm before they change anything. LogBlock shows what it found and asks the player to type `/lb yes` or `/lb no`:

```
Searching Block changes from player Griefer in the last 120 minutes within 10 blocks of you in world:
124 blocks found.
Rollback 124 blocks?
Type /lb yes to confirm or /lb no to cancel (60 seconds).
```

| Command | Question | Config option |
|---|---|---|
| `/lb rollback` | "Rollback N blocks?" | `questioner.askRollbacks` |
| after a rollback | "Also delete the log of the rolled back blocks?" On `/lb yes`, runs clearlog for the same params. Asked only if the player has `logblock.clearlog`. | `questioner.askClearLogAfterRollback` |
| `/lb redo` | "Redo N blocks?" | `questioner.askRedos` |
| `/lb clearlog` | "Delete N log entries?" Adds "This can't be undone." if `clearlog.dumpDeletedLog` is off. | `questioner.askClearLogs` |

All four options are `true` by default. They keep the `questioner.` prefix from the old LogBlockQuestioner plugin, so existing `config.yml` files keep working.

## When LogBlock asks

LogBlock asks only when all of the following are true:
- the sender is a player, not the console;
- the query is not `silent`;
- the config option for the command is `true`.

So the console, the automatic clearlog from `clearlog.keepLogDays`, and the tool with the default (`silent`) tool query run without asking. Same rules as the old LogBlockQuestioner.

## How it works

The code is in `src/de/diddiz/LogBlock/CommandsHandler.java`: `needsConfirmation`, `askConfirmation`, `PendingConfirmation` and the `yes`/`no` branch in `onCommand`.

- The command does its search, then stores the rest of its work in the player's `Session` (`pendingConfirmation`) and returns.
- Each player has one pending confirmation. A new rollback/redo/clearlog replaces the old one.
- `/lb yes` runs the stored action in a new async task. After 60 seconds (`CONFIRMATION_SECONDS`) it is refused: "... wasn't confirmed within 60 seconds. Run the command again."
- `/lb no` drops it: "Rollback aborted".
- Rollback and redo keep the blocks they found. `/lb yes` applies exactly the N blocks from the question.
- Clearlog runs again on `/lb yes` with a `silent` copy of the params: count, dump, delete. The `DELETE` uses the same `WHERE` as the question. Entries logged in the meantime that match it are deleted too, and the final message shows the real count.
- The params are copied when the question is asked, because the tool reuses one params object for every click.
