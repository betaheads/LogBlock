# Chest access logging

With `logChestAccess: true` in a world config (`plugins/LogBlock/<world>.yml`), LogBlock logs which items players put into and take out of chests, furnaces and dispensers. The entries go to the `<table>-chest` table and show up in `/lb lookup chestaccess` and in tool lookups on containers. Rollback puts the items back or takes them out again.

It used to need the Spout plugin, which provided inventory open/close events. Spout is gone (not in any Maven repo, dead download URL), and Bukkit for b1.7.3 has no such events, so `LBChestAccessListener` now gets the same information from Poseidon itself.

## How it works

1. **Open.** A player right-clicks a chest, furnace or dispenser (`PLAYER_INTERACT`, Monitor priority, not cancelled). LogBlock takes a snapshot of the container's contents. For a chest it also takes a snapshot of every adjacent chest, because the game opens them together as a double chest.
2. **Did it open?** A task runs every tick. On the next tick it checks the player's open window (`EntityPlayer.activeContainer`). If the player has no window open, the chest didn't open (a solid block above it, or another plugin denied it), and the snapshot is dropped.
3. **Close.** The window counts as closed as soon as the player's open window changes, or the player quits. That covers closing it with Esc, walking away, dying, being kicked and the server closing it. LogBlock then diffs the contents against the snapshot (`BukkitUtils.compareInventories`). Each changed item type goes to `Consumer.queueChestAccess`: a positive amount means the player put items in, a negative amount means they took items out.
4. **Disable.** On server stop, containers that are still open are logged before the queue is saved.

Changes are summed per item type between open and close: if a player takes 10 stone and puts 4 back, one entry with -6 stone is logged.

## Compared to the Spout version

- **Double chests:** each half is logged at its own coordinates. Spout logged the whole 54-slot diff at the clicked block.
- **Two players with the same container open:** each change is logged once. When one player closes the container, the other player's snapshot restarts from the current contents. With Spout, both players got every change.
- **Burning furnaces:** when a burning furnace is broken or blown up, its contents are now logged (the type check used to have only 23, 54 and 61).
- **Broken while open:** if the container is broken or replaced while a player has it open, nothing is logged for that player. The contents are logged with the break (`Consumer.queueContainerBreak`), and the open container would otherwise look emptied.

## Limitations

- The diff covers everything that changed while the window was open. Furnace smelting, a dispenser firing, or a plugin changing the contents counts as the player's change. The Spout version worked the same way.
- If two players have the same container open at once, a change made by one of them can be logged for whichever of them closes first.
- Only containers opened with a right click on the block are tracked. Inventories that plugins open some other way are not.

## Server internals used

The listener uses classes from Poseidon's `server.jar` that are not part of the Bukkit API:

| What | Why |
|---|---|
| `CraftPlayer.getHandle()`, `EntityHuman.activeContainer`, `EntityHuman.defaultContainer` | Is a window open, and is it still the same one |
| `CraftWorld.getHandle().getTileEntity(x, y, z)` | The container's tile entity: snapshots are read from it, and it shows if the block was replaced |
| `CraftInventory(IInventory)` | Reads the tile entity's contents through the Bukkit inventory API |

These names are not obfuscated and are the same in CraftBukkit for b1.7.3. If the classes are missing (another server), LogBlock prints a warning on startup and only chest access logging is off. The listener is registered in `LogBlock.onEnable`.

## Test

`tests/chest-access/run.sh` checks all of the above on a real server with two bots, see [tests/README.md](../tests/README.md).
