package de.diddiz.LogBlock;

import static de.diddiz.util.Utils.download;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FilenameFilter;
import java.io.IOException;
import java.net.URL;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Event.Priority;
import org.bukkit.event.Event.Type;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.config.Configuration;
import com.nijiko.permissions.PermissionHandler;
import com.nijikokun.bukkit.Permissions.Permissions;
import de.diddiz.LogBlock.QueryParams.BlockChangeType;
import de.diddiz.util.MySQLConnectionPool;

public class LogBlock extends JavaPlugin
{
	private Logger log;
	private Config config;
	private MySQLConnectionPool pool;
	private Consumer consumer = null;
	private CommandsHandler commandsHandler;
	private Updater updater = null;
	private Timer timer = null;
	private LBChestAccessListener chestAccessListener = null;
	private PermissionHandler permissions = null;
	private boolean usePermissionsPlugin = false;
	private volatile PermissionGroups permissionGroups;
	private boolean errorAtLoading = false;
	private final Map<Integer, Session> sessions = new HashMap<Integer, Session>();

	public Config getConfig() {
		return config;
	}

	public Consumer getConsumer() {
		return consumer;
	}

	public CommandsHandler getCommandsHandler() {
		return commandsHandler;
	}

	Updater getUpdater() {
		return updater;
	}

	@Override
	public void onLoad() {
		log = getServer().getLogger();
		try {
			updater = new Updater(this);
			log.info("[LogBlock] Version check: " + updater.checkVersion());
			config = new Config(this);
			// No lib/mysql-connector-java-bin.jar needed: the MySQL driver (com.mysql.jdbc.Driver,
			// Connector/J 5.1.14) is bundled in Poseidon's server.jar and visible to all plugins.
			log.info("[LogBlock] Connecting to " + config.user + "@" + config.url + "...");
			pool = new MySQLConnectionPool(config.url, config.user, config.password);
			getConnection().close();
			if (updater.update())
				config = new Config(this);
			updater.checkTables();
			final File[] imports = new File("plugins/LogBlock/import/").listFiles(new FilenameFilter() {
				@Override
				public boolean accept(File dir, String name) {
					return name.toLowerCase().endsWith(".sql");
				}
			});
			if (imports != null && imports.length > 0) {
				log.info("[LogBlock] Found " + imports.length + "imports.");
				Connection conn = null;
				try {
					conn = getConnection();
					conn.setAutoCommit(false);
					final Statement st = conn.createStatement();
					for (final File sqlFile : imports)
						try {
							log.info("[LogBlock] Trying to import " + sqlFile.getName() + " ...");
							final BufferedReader reader = new BufferedReader(new FileReader(sqlFile));
							String line = null;
							while ((line = reader.readLine()) != null)
								st.addBatch(line);
							st.executeBatch();
							conn.commit();
							reader.close();
							sqlFile.delete();
							log.info("[LogBlock] Successfully imported " + sqlFile.getName() + ".");
						} catch (final Exception ex) {
							log.log(Level.WARNING, "[LogBlock] Failed to import " + sqlFile.getName() + ": ", ex);
							sqlFile.renameTo(new File("plugins/LogBlock/import/" + sqlFile.getName() + ".failed"));
						}
					st.close();
					log.info("[LogBlock] Successfully imported stored queue.");
				} catch (final Exception ex) {
					log.log(Level.WARNING, "[LogBlock] Error while importing: ", ex);
				} finally {
					if (conn != null)
						conn.close();
				}
			}
		} catch (final Exception ex) {
			log.log(Level.SEVERE, "[LogBlock] Error while loading: ", ex);
			errorAtLoading = true;
			return;
		}
		consumer = new Consumer(this);
	}

	@Override
	public void onEnable() {
		final PluginManager pm = getServer().getPluginManager();
		if (errorAtLoading) {
			pm.disablePlugin(this);
			return;
		}
		if (pm.getPlugin("WorldEdit") == null && !new File("lib/WorldEdit.jar").exists() && !new File("WorldEdit.jar").exists())
			try {
				download(log, new URL("http://diddiz.insane-architects.net/download/WorldEdit.jar"), new File("lib/WorldEdit.jar"));
				log.info("[LogBlock] You've to restart/reload your server now.");
				pm.disablePlugin(this);
				return;
			} catch (final Exception ex) {
				log.warning("[LogBlock] Failed to download WorldEdit. You may have to download it manually. You don't have to install it, just place the jar in the lib folder.");
			}
		commandsHandler = new CommandsHandler(this);
		getCommand("lb").setExecutor(commandsHandler);
		applyPermissionGroups(config.permissionGroups);
		log.info("[LogBlock] Permissions: " + permissionGroups.getGroups().size() + " groups in config.yml (permissions.groups).");
		usePermissionsPlugin = config.usePermissionsPlugin;
		if (usePermissionsPlugin)
			if (pm.getPlugin("Permissions") != null) {
				permissions = ((Permissions)pm.getPlugin("Permissions")).getHandler();
				log.info("[LogBlock] Also using the Permissions plugin (permissions.usePermissionsPlugin).");
			} else
				log.info("[LogBlock] Also using Bukkit permissions (permissions.usePermissionsPlugin).");
		if (config.keepLogDays >= 0) {
			final QueryParams params = new QueryParams(this);
			params.minutes = config.keepLogDays * -1440;
			params.bct = BlockChangeType.ALL;
			for (final World world : getServer().getWorlds())
				if (config.worlds.containsKey(world.getName().hashCode())) {
					params.world = world;
					try {
						commandsHandler.new CommandClearLog(new ConsoleCommandSender(getServer()), params.clone(), true);
					} catch (final Exception ex) {
						log.severe("Failed to schedule ClearLog: " + ex.getMessage());
					}
				}
		}
		final LBBlockListener lbBlockListener = new LBBlockListener(this);
		final LBPlayerListener lbPlayerListener = new LBPlayerListener(this);
		final LBEntityListener lbEntityListener = new LBEntityListener(this);
		pm.registerEvent(Type.PLAYER_INTERACT, new LBToolListener(this), Priority.Normal, this);
		if (config.logBlockPlacings) {
			pm.registerEvent(Type.BLOCK_PLACE, lbBlockListener, Priority.Monitor, this);
			pm.registerEvent(Type.PLAYER_BUCKET_EMPTY, lbPlayerListener, Priority.Monitor, this);
		}
		if (config.logBlockBreaks) {
			pm.registerEvent(Type.BLOCK_BREAK, lbBlockListener, Priority.Monitor, this);
			pm.registerEvent(Type.PLAYER_BUCKET_FILL, lbPlayerListener, Priority.Monitor, this);
			pm.registerEvent(Type.BLOCK_FROMTO, lbBlockListener, Priority.Monitor, this);
		}
		if (config.logSignTexts)
			pm.registerEvent(Type.SIGN_CHANGE, lbBlockListener, Priority.Monitor, this);
		if (config.logFire)
			pm.registerEvent(Type.BLOCK_BURN, lbBlockListener, Priority.Monitor, this);
		if (config.logSnowForm)
			pm.registerEvent(Type.BLOCK_FORM, lbBlockListener, Priority.Monitor, this);
		if (config.logSnowFade)
			pm.registerEvent(Type.BLOCK_FADE, lbBlockListener, Priority.Monitor, this);
		if (config.logExplosions)
			pm.registerEvent(Type.ENTITY_EXPLODE, lbEntityListener, Priority.Monitor, this);
		if (config.logLeavesDecay)
			pm.registerEvent(Type.LEAVES_DECAY, lbBlockListener, Priority.Monitor, this);
		if (config.logChestAccess)
			// Built on Poseidon's server internals (net.minecraft.server / org.bukkit.craftbukkit), see LBChestAccessListener
			try {
				final LBChestAccessListener listener = new LBChestAccessListener(this);
				if (getServer().getScheduler().scheduleSyncRepeatingTask(this, listener, 1, 1) < 0)
					log.warning("[LogBlock] Failed to schedule chest access logging. Items put into / taken out of containers are not logged.");
				else {
					pm.registerEvent(Type.PLAYER_INTERACT, listener, Priority.Monitor, this);
					pm.registerEvent(Type.PLAYER_QUIT, listener, Priority.Monitor, this);
					chestAccessListener = listener;
				}
			} catch (final LinkageError ex) {
				log.warning("[LogBlock] Chest access logging needs Project Poseidon (or CraftBukkit for b1.7.3), not available: " + ex + ". Items put into / taken out of containers are not logged.");
			}
		if (config.logButtonsAndLevers)
			pm.registerEvent(Type.PLAYER_INTERACT, lbPlayerListener, Priority.Monitor, this);
		if (config.logKills)
			pm.registerEvent(Type.ENTITY_DAMAGE, lbEntityListener, Priority.Monitor, this);
		if (config.logChat) {
			pm.registerEvent(Type.PLAYER_CHAT, lbPlayerListener, Priority.Monitor, this);
			pm.registerEvent(Type.PLAYER_COMMAND_PREPROCESS, lbPlayerListener, Priority.Monitor, this);
		}
		if (config.useBukkitScheduler) {
			if (getServer().getScheduler().scheduleAsyncRepeatingTask(this, consumer, config.delayBetweenRuns * 20, config.delayBetweenRuns * 20) > 0)
				log.info("[LogBlock] Scheduled consumer with bukkit scheduler.");
			else {
				log.warning("[LogBlock] Failed to schedule consumer with bukkit scheduler. Now trying schedule with timer.");
				timer = new Timer();
				timer.scheduleAtFixedRate(consumer, config.delayBetweenRuns * 1000, config.delayBetweenRuns * 1000);
			}
		} else {
			timer = new Timer();
			timer.scheduleAtFixedRate(consumer, config.delayBetweenRuns * 1000, config.delayBetweenRuns * 1000);
			log.info("[LogBlock] Scheduled consumer with timer.");
		}
		log.info("LogBlock v" + getDescription().getVersion() + " by DiddiZ enabled.");
	}

	@Override
	public void onDisable() {
		if (timer != null)
			timer.cancel();
		getServer().getScheduler().cancelTasks(this);
		if (chestAccessListener != null) {
			chestAccessListener.logAll();
			chestAccessListener = null;
		}
		if (consumer != null && consumer.getQueueSize() > 0) {
			log.info("[LogBlock] Waiting for consumer ...");
			int lastSize = -1, fails = 0;
			while (consumer.getQueueSize() > 0) {
				log.info("[LogBlock] Remaining queue size: " + consumer.getQueueSize());
				if (lastSize == consumer.getQueueSize()) {
					fails++;
					log.info("[LogBlock] Remaining tries: " + (10 - fails));
				} else
					fails = 0;
				if (fails == 10) {
					log.info("Unable to save queue to database. Trying to write to a local file.");
					try {
						consumer.writeToFile();
					} catch (final FileNotFoundException ex) {
						log.info("Failed to write. Given up.");
						break;
					}
				}
				lastSize = consumer.getQueueSize();
				consumer.run();
			}
		}
		if (pool != null)
			pool.close();
		log.info("LogBlock disabled.");
	}

	/**
	 * The console has all permissions. A player has a permission if one of their groups in config.yml gives it, or, with
	 * permissions.usePermissionsPlugin, if the Permissions plugin or Bukkit permissions give it. See docs/permissions.md.
	 * @param permission e.g. "logblock.lookup"
	 */
	boolean hasPermission(CommandSender sender, String permission) {
		if (!(sender instanceof Player))
			return true;
		final Player player = (Player)sender;
		if (permissionGroups.has(player.getName(), player.isOp(), permission))
			return true;
		if (!usePermissionsPlugin)
			return false;
		if (permissions != null)
			return permissions.has(player, permission);
		return player.hasPermission(permission);
	}

	public PermissionGroups getPermissionGroups() {
		return permissionGroups;
	}

	/** Reads permissions.groups from config.yml again, for /lb group reload. If that fails, the old groups stay. */
	synchronized PermissionGroups reloadPermissionGroups() throws IOException {
		return applyPermissionGroups(PermissionGroups.parse(loadPermissionGroupsNode()));
	}

	/**
	 * Adds the player to or removes them from the players of a group in config.yml, saves it and reloads the groups,
	 * for /lb group add|remove.
	 * @return the group's name as written in config.yml
	 * @throws IllegalArgumentException no such group, already in it / not in it
	 */
	synchronized String changeGroup(String group, String player, boolean add) throws IOException {
		final Object node = loadPermissionGroupsNode();
		final String name = add ? PermissionGroups.addPlayer(node, group, player) : PermissionGroups.removePlayer(node, group, player);
		getConfiguration().setHeader(Config.HEADER);
		if (!getConfiguration().save())
			throw new IOException("Error while writing to config.yml");
		applyPermissionGroups(PermissionGroups.parse(node));
		return name;
	}

	/** config.yml is loaded fresh, so changes made in the file meanwhile are kept */
	private Object loadPermissionGroupsNode() throws IOException {
		final Configuration config = getConfiguration();
		try {
			config.load();
		} catch (final RuntimeException ex) {
			// SnakeYAML's message has the line and column, the stack trace says nothing more
			log.warning("[LogBlock] Can't read config.yml: " + ex.getMessage());
			throw new IOException("config.yml has a mistake (see server log), nothing changed");
		}
		final Object node = config.getProperty("permissions.groups");
		if (!(node instanceof Map))
			throw new IOException("config.yml can't be read or has no permissions.groups, nothing changed");
		return node;
	}

	private PermissionGroups applyPermissionGroups(PermissionGroups groups) {
		for (final String warning : groups.getWarnings())
			log.warning("[LogBlock] config.yml: " + warning);
		permissionGroups = groups;
		return groups;
	}

	public Connection getConnection() {
		try {
			return pool.getConnection();
		} catch (final Exception ex) {
			log.log(Level.SEVERE, "[LogBlock] Error while fetching connection", ex);
			return null;
		}
	}

	public Session getSession(String playerName) {
		Session session = sessions.get(playerName.hashCode());
		if (session == null) {
			session = new Session(this);
			sessions.put(playerName.hashCode(), session);
		}
		return session;
	}

	/**
	 * @param params
	 * QueryParams that contains the needed columns (all other will be filled with default values) and the params. World is required.
	 */
	public List<BlockChange> getBlockChanges(QueryParams params) throws SQLException {
		final Connection conn = getConnection();
		Statement state = null;
		ResultSet rs = null;
		if (conn == null)
			throw new SQLException("No connection");
		try {
			state = conn.createStatement();
			rs = state.executeQuery(params.getQuery());
			final List<BlockChange> blockchanges = new ArrayList<BlockChange>();
			while (rs.next())
				blockchanges.add(new BlockChange(rs, params));
			return blockchanges;
		} finally {
			if (state != null)
				state.close();
			conn.close();
		}
	}
}
