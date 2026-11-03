// SPOUT DISABLED (see docs/spout.md): this whole class is commented out because it is built on Spout's API
// (org.getspout.spoutapi.*), and Spout is not available as a build dependency.
//
// How chest access logging works:
// 1. onInventoryOpen: remember a compressed snapshot of the container contents per player.
// 2. onInventoryClose: take a new snapshot, diff it against the remembered one
//    (BukkitUtils.compareInventories) and queue every changed item stack with
//    Consumer.queueChestAccess (positive amount = put in, negative = taken out).
//
// To bring it back, either add Spout as a dependency again, or rewrite these two handlers on
// top of any other source of "player opened/closed a container at location X" events.
// It is registered in LogBlock.onEnable (see the "SPOUT DISABLED" comments there).
//
// package de.diddiz.LogBlock;
//
// import static de.diddiz.util.BukkitUtils.compareInventories;
// import static de.diddiz.util.BukkitUtils.compressInventory;
// import static de.diddiz.util.BukkitUtils.rawData;
// import java.util.HashMap;
// import java.util.Map;
// import org.bukkit.Location;
// import org.bukkit.inventory.ItemStack;
// import org.getspout.spoutapi.event.inventory.InventoryCloseEvent;
// import org.getspout.spoutapi.event.inventory.InventoryListener;
// import org.getspout.spoutapi.event.inventory.InventoryOpenEvent;
//
// class LBChestAccessListener extends InventoryListener
// {
// 	private final Consumer consumer;
// 	private final Map<Integer, ItemStack[]> containers = new HashMap<Integer, ItemStack[]>();
//
// 	LBChestAccessListener(LogBlock logblock) {
// 		consumer = logblock.getConsumer();
// 	}
//
// 	@Override
// 	public void onInventoryClose(InventoryCloseEvent event) {
// 		if (!event.isCancelled() && event.getLocation() != null && containers.containsKey(event.getPlayer().getName().hashCode())) {
// 			final String playerName = event.getPlayer().getName();
// 			final Location loc = event.getLocation();
// 			final ItemStack[] before = containers.get(playerName.hashCode());
// 			final ItemStack[] after = compressInventory(event.getInventory().getContents());
// 			final ItemStack[] diff = compareInventories(before, after);
// 			for (final ItemStack item : diff)
// 				consumer.queueChestAccess(playerName, loc, loc.getWorld().getBlockTypeIdAt(loc), (short)item.getTypeId(), (short)item.getAmount(), rawData(item));
// 			containers.remove(playerName.hashCode());
// 		}
// 	}
//
// 	@Override
// 	public void onInventoryOpen(InventoryOpenEvent event) {
// 		if (!event.isCancelled() && event.getLocation() != null)
// 			containers.put(event.getPlayer().getName().hashCode(), compressInventory(event.getInventory().getContents()));
// 	}
// }
