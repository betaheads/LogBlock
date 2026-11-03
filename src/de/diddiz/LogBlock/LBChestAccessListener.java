package de.diddiz.LogBlock;

import static de.diddiz.util.BukkitUtils.compareInventories;
import static de.diddiz.util.BukkitUtils.compressInventory;
import static de.diddiz.util.BukkitUtils.isContainer;
import static de.diddiz.util.BukkitUtils.rawData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.minecraft.server.Container;
import net.minecraft.server.EntityPlayer;
import net.minecraft.server.IInventory;
import net.minecraft.server.TileEntity;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.inventory.CraftInventory;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerListener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Logs items players put into and take out of chests, furnaces and dispensers (logChestAccess).
 * Used to be built on Spout's InventoryOpenEvent / InventoryCloseEvent; Bukkit for b1.7.3 has no such events,
 * so this listener uses Poseidon's server internals instead (see docs/chest-access.md):
 * <ol>
 * <li>Open: a right click on a container block (PLAYER_INTERACT, Monitor) remembers a snapshot of its contents.
 * For a chest, every adjacent chest is part of the opened double chest, the same way BlockChest.interact builds it.</li>
 * <li>On the next tick ({@link #run()}): if the player's activeContainer is still their defaultContainer, the
 * container didn't open (e.g. a solid block above the chest) and the snapshot is dropped. Otherwise that container is
 * remembered.</li>
 * <li>Close: as soon as the player's activeContainer is no longer that container (window closed, walked away, died,
 * kicked) or the player quits, the contents are diffed against the snapshot and each changed item stack goes to
 * Consumer.queueChestAccess (positive amount = put in, negative = taken out).</li>
 * </ol>
 */
class LBChestAccessListener extends PlayerListener implements Runnable
{
	private static final BlockFace[] HORIZONTAL = new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};
	private final Consumer consumer;
	private final Map<Integer, WorldConfig> worlds;
	/** Containers players have open, by player name. Only accessed from the main thread. */
	private final Map<String, OpenContainer> openContainers = new HashMap<String, OpenContainer>();

	LBChestAccessListener(LogBlock logblock) {
		consumer = logblock.getConsumer();
		worlds = logblock.getConfig().worlds;
	}

	@Override
	public void onPlayerInteract(PlayerInteractEvent event) {
		if (event.isCancelled() || event.getAction() != Action.RIGHT_CLICK_BLOCK)
			return;
		final Block block = event.getClickedBlock();
		final int type = block.getTypeId();
		if (!isContainer(type))
			return;
		final WorldConfig wcfg = worlds.get(block.getWorld().getName().hashCode());
		if (wcfg == null || !wcfg.logChestAccess)
			return;
		final Player player = event.getPlayer();
		// A container is normally closed before the next one is opened; this handles a missed close
		final OpenContainer previous = openContainers.remove(player.getName());
		if (previous != null && previous.container != null)
			log(previous);
		final List<Part> parts = new ArrayList<Part>(2);
		addPart(parts, block);
		if (type == 54)
			for (final BlockFace face : HORIZONTAL) {
				final Block neighbour = block.getRelative(face);
				if (neighbour.getTypeId() == 54)
					addPart(parts, neighbour);
			}
		if (!parts.isEmpty())
			openContainers.put(player.getName(), new OpenContainer(player, parts));
	}

	@Override
	public void onPlayerQuit(PlayerQuitEvent event) {
		final OpenContainer oc = openContainers.remove(event.getPlayer().getName());
		if (oc != null && oc.container != null)
			log(oc);
	}

	/**
	 * Runs every tick: detects whether the clicked containers really opened and when they get closed.
	 */
	@Override
	public void run() {
		if (openContainers.isEmpty())
			return;
		final Iterator<OpenContainer> it = openContainers.values().iterator();
		while (it.hasNext()) {
			final OpenContainer oc = it.next();
			final EntityPlayer handle = ((CraftPlayer)oc.player).getHandle();
			final boolean online = oc.player.isOnline();
			if (oc.container == null) {
				if (online && handle.activeContainer != handle.defaultContainer)
					oc.container = handle.activeContainer;
				else
					it.remove(); // The click didn't open the container
			} else if (!online || handle.activeContainer != oc.container) {
				it.remove();
				log(oc);
			}
		}
	}

	/**
	 * Logs the changes of all containers that are still open. Called on disable.
	 */
	void logAll() {
		final List<OpenContainer> all = new ArrayList<OpenContainer>(openContainers.values());
		openContainers.clear();
		for (final OpenContainer oc : all)
			if (oc.container != null)
				log(oc);
	}

	/**
	 * oc must already be removed from openContainers.
	 */
	private void log(OpenContainer oc) {
		final String playerName = oc.player.getName();
		for (final Part part : oc.parts) {
			final World world = part.loc.getWorld();
			// Container broken or replaced meanwhile: its contents were already logged with the break
			if (tileEntityAt(world, part.loc) != part.tileEntity)
				continue;
			final ItemStack[] after = snapshot(part.tileEntity);
			for (final ItemStack item : compareInventories(part.before, after))
				consumer.queueChestAccess(playerName, part.loc, world.getBlockTypeIdAt(part.loc), (short)item.getTypeId(), (short)item.getAmount(), rawData(item));
			// Other players who have the same container open diff from now on, so each change is logged only once
			for (final OpenContainer other : openContainers.values())
				for (final Part otherPart : other.parts)
					if (otherPart.tileEntity == part.tileEntity)
						otherPart.before = snapshot(part.tileEntity);
		}
	}

	private static void addPart(List<Part> parts, Block block) {
		final Location loc = block.getLocation();
		final TileEntity tileEntity = tileEntityAt(block.getWorld(), loc);
		if (tileEntity instanceof IInventory)
			parts.add(new Part(loc, tileEntity, snapshot(tileEntity)));
	}

	private static TileEntity tileEntityAt(World world, Location loc) {
		return ((CraftWorld)world).getHandle().getTileEntity(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
	}

	/**
	 * @return compressed copy of the contents; compareInventories modifies its first argument, so never share one
	 */
	private static ItemStack[] snapshot(TileEntity tileEntity) {
		return compressInventory(new CraftInventory((IInventory)tileEntity).getContents());
	}

	private static class OpenContainer
	{
		final Player player;
		final List<Part> parts;
		/** The opened window, null until the next tick confirms it opened. */
		Container container = null;

		OpenContainer(Player player, List<Part> parts) {
			this.player = player;
			this.parts = parts;
		}
	}

	/** One container block, i.e. one half of a double chest. */
	private static class Part
	{
		final Location loc;
		final TileEntity tileEntity;
		ItemStack[] before;

		Part(Location loc, TileEntity tileEntity, ItemStack[] before) {
			this.loc = loc;
			this.tileEntity = tileEntity;
			this.before = before;
		}
	}
}
