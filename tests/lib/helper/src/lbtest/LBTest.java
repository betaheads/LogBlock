package lbtest;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.ContainerBlock;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Test fixture for the end-to-end tests (ChestTest, FreshInstallTest), used from the server console. Changes blocks
 * without firing events, so LogBlock logs nothing for them. Never install it on a real server.
 * <ul>
 * <li>/lbt setup player1 [player2 ...]: stone platform at y=99 around 0,0 with
 * chest S (2,100,0): 10 stone, 5 dirt; double chest D1 (-2,100,0): 7 cobblestone + D2 (-2,100,1): 9 planks;
 * chest B (0,100,3) with a stone block above, so it can't open: 4 sand; empty dispenser (0,100,-3).
 * Teleports the players to 0.5,100,0.5 and clears their inventories; player1 gets 20 glass in hotbar slot 0.</li>
 * <li>/lbt air x y z: sets the block to air.</li>
 * <li>/lbt give player id: puts one item of type id into hotbar slot 0, the slot the bots hold.</li>
 * </ul>
 */
public class LBTest extends JavaPlugin
{
	@Override
	public void onEnable() {}

	@Override
	public void onDisable() {}

	@Override
	public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
		final World world = getServer().getWorlds().get(0);
		if (args.length >= 1 && args[0].equals("setup")) {
			for (int x = -5; x <= 5; x++)
				for (int z = -5; z <= 5; z++) {
					world.getBlockAt(x, 99, z).setTypeId(1);
					for (int y = 100; y <= 104; y++)
						world.getBlockAt(x, y, z).setTypeId(0);
				}
			chest(world, 2, 100, 0, new ItemStack(1, 10), new ItemStack(3, 5));
			chest(world, -2, 100, 0, new ItemStack(4, 7));
			chest(world, -2, 100, 1, new ItemStack(5, 9));
			chest(world, 0, 100, 3, new ItemStack(12, 4));
			world.getBlockAt(0, 101, 3).setTypeId(1);
			world.getBlockAt(0, 100, -3).setTypeId(23);
			for (int i = 1; i < args.length; i++) {
				final Player p = getServer().getPlayer(args[i]);
				p.teleport(new Location(world, 0.5, 100, 0.5));
				p.getInventory().clear();
				if (i == 1)
					p.getInventory().setItem(0, new ItemStack(20, 20));
			}
			System.out.println("LBT setup done");
			return true;
		}
		if (args.length == 4 && args[0].equals("air")) {
			world.getBlockAt(Integer.parseInt(args[1]), Integer.parseInt(args[2]), Integer.parseInt(args[3])).setTypeId(0);
			System.out.println("LBT air done");
			return true;
		}
		if (args.length == 3 && args[0].equals("give")) {
			getServer().getPlayer(args[1]).getInventory().setItem(0, new ItemStack(Integer.parseInt(args[2]), 1));
			System.out.println("LBT give done");
			return true;
		}
		return false;
	}

	private static void chest(World world, int x, int y, int z, ItemStack... items) {
		final Block block = world.getBlockAt(x, y, z);
		block.setTypeId(54);
		final ContainerBlock state = (ContainerBlock)block.getState();
		for (int i = 0; i < items.length; i++)
			state.getInventory().setItem(i, items[i]);
	}
}
