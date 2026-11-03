# Spout: chest access logging (disabled)

## What it did

With `logChestAccess: true` in a world config, LogBlock logged which items players put into and took out of chests, furnaces and dispensers.

- Spout provided `InventoryOpenEvent` and `InventoryCloseEvent` (`org.getspout.spoutapi.event.inventory`).
- `LBChestAccessListener` stores a snapshot of the container contents when a player opens it.
- When the player closes the container, the listener diffs the new contents against the snapshot (`BukkitUtils.compareInventories`).
- Each changed item stack goes to `Consumer.queueChestAccess`: a positive amount means the player put items in, a negative amount means they took items out.
- The data lands in the `<table>-chest` table and shows up in `/lb lookup chestaccess` and in tool lookups on containers.

If Spout was missing, LogBlock used to download it on startup from `ci.getspout.org` and enable it.

## Why it is disabled

Spout is not in any Maven repository, so the Maven build can't compile against it. Its old download URL is dead as well. Spout is not installed on our server either.

## What is commented out

Search the code for `SPOUT DISABLED`:

| Where | What |
|---|---|
| `src/de/diddiz/LogBlock/LBChestAccessListener.java` | The whole class. The header explains how it works. |
| `src/de/diddiz/LogBlock/LogBlock.java`, `onEnable` | Two blocks: the Spout auto-download, and the registration of `LBChestAccessListener`. |
| `pom.xml` | A placeholder comment where the dependency would go. |

## Current behavior

- **Not logged:** items players put into or take out of containers.
- **Warning:** if `logChestAccess` is on, a warning is printed on startup.
- **Still logged** (when `logChestAccess` is on), because it doesn't need Spout: the contents of a container that a player breaks or an explosion destroys. They are logged as taken out (`Consumer.queueContainerBreak`).
- **Still works:** the `<table>-chest` table is still created, and `/lb lookup chestaccess` shows old data and those container-break entries.

## How to restore

1. Get a container open/close event source that works on Poseidon. There are two options:
   - A Spout jar compatible with Poseidon / b1.7.3. It is not verified that one exists or works.
   - Better: rewrite `onInventoryOpen` and `onInventoryClose` on top of other events that give "player opened/closed a container at location X". Then Spout is not needed at all, and only the listener changes. The diff and queue logic stays the same.
2. If you use Spout, add it to `pom.xml` the same way as `server.jar`:
   ```xml
   <dependency>
       <groupId>org.getspout</groupId>
       <artifactId>spout</artifactId>
       <version>dev</version>
       <scope>system</scope>
       <systemPath>${project.basedir}/testsrv/plugins/Spout.jar</systemPath>
   </dependency>
   ```
   Also add `Spout` to `softdepend` in `src/resources/plugin.yml`. It currently lists only `BukkitContrib`, Spout's old name.
3. Uncomment `LBChestAccessListener` and the registration block in `LogBlock.onEnable`, then remove the warning below it.
4. Don't restore the auto-download block as it was, because the URL is dead. Remove it or point it to a working URL.
