package de.diddiz.LogBlock;

import static de.diddiz.util.BukkitUtils.friendlyWorldname;
import static de.diddiz.util.Utils.parseTimeSpec;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.zip.DataFormatException;
import org.bukkit.Material;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.util.config.Configuration;

public class Config
{
	public final Map<Integer, WorldConfig> worlds;
	public final String url, user, password;
	public final int delayBetweenRuns, forceToProcessAtLeast, timePerRun;
	public final boolean useBukkitScheduler;
	public final int keepLogDays;
	public final boolean dumpDeletedLog;
	public boolean logBlockPlacings, logBlockBreaks, logSignTexts, logExplosions, logFire, logLeavesDecay, logLavaFlow, logWaterFlow, logChestAccess, logButtonsAndLevers, logKills, logChat, logSnowForm, logSnowFade;
	public final boolean logCreeperExplosionsAsPlayerWhoTriggeredThese;
	public final LogKillsLevel logKillsLevel;
	public final Set<Integer> dontRollback, replaceAnyway;
	public final int rollbackMaxTime, rollbackMaxArea;
	public final QueryParams toolQuery, toolBlockQuery;
	public final int defaultDist, defaultTime;
	public final int linesPerPage, linesLimit;
	public final int toolID, toolblockID;
	public final boolean askRollbacks, askRedos, askClearLogs, askClearLogAfterRollback;
	public final Set<Integer> hiddenPlayers, hiddenBlocks;
	public final boolean usePermissionsPlugin;
	/** As loaded at startup; /lb group reload replaces LogBlock's copy, see LogBlock.getPermissionGroups() */
	public final PermissionGroups permissionGroups;

	/**
	 * config.yml is rewritten on every start and by /lb group add|remove, which drops comments; this header stays.
	 * Every line must start with #. Keep the commands in sync with /lb commands and docs/permissions.md.
	 */
	static final String[] HEADER = {
			"# LogBlock config. LogBlock rewrites this file on every start: this comment block stays, other comments are lost.",
			"#",
			"# ===== Permissions: permissions.groups =====",
			"# Who may use which /lb command. A player gets the permissions of all groups they are in:",
			"#   default: true   every player is in the group",
			"#   ops: true       every op (ops.txt) is in the group",
			"#   players: [...]  these players are in the group, by nickname (case doesn't matter)",
			"# Permissions: " + PermissionGroups.join(PermissionGroups.PERMISSIONS) + ",",
			"#   '*' (all of them, in quotes) or all. The console has all permissions.",
			"# After editing this file type /lb group reload, no restart needed.",
			"# permissions.usePermissionsPlugin: true also asks a permissions plugin (Permissions or Bukkit permissions).",
			"#",
			"# ===== Commands =====  [permission needed], the ones without need none",
			"# /lb help, /lb commands, /lb params         help in game",
			"# /lb permissions                            your permissions and groups",
			"# /lb logging                                what is logged in your world",
			"# /lb lookup <params>                        search the log [lookup], also without lookup: /lb player Steve area 10",
			"# /lb page <n>, /lb next, /lb prev           pages of the last lookup",
			"# /lb tp <n>                                 teleport to entry n of the last lookup [tp]",
			"# /lb tp <params>                            teleport to the first change found [tp]",
			"# /lb me                                     your own stats [me]",
			"# /lb tool  (/lb t)                          get the tool (lookup.toolID, wood pickaxe), right click a block [tool]",
			"# /lb tool on|off                            turn your tool on or off",
			"# /lb tool <params>                          your own tool query [lookup]",
			"# /lb tool default                           back to lookup.toolQuery and mode lookup",
			"# /lb tool mode <mode>                       what the tool does: lookup [lookup],",
			"#                                            rollback, redo, writelogfile [rollback], clearlog [clearlog]",
			"# /lb toolblock ...  (/lb tb)                the same for the tool block (lookup.toolblockID, bedrock) [toolblock]",
			"# /lb rollback <params>  (/lb rb, /lb undo)  undo block changes [rollback],",
			"#                                            within rollback.maxTime and rollback.maxArea unless [ignoreRestrictions]",
			"# /lb redo <params>                          redo rolled back changes [rollback]",
			"# /lb yes, /lb no                            confirm or cancel a rollback, redo or clearlog (questioner.*)",
			"# /lb writelogfile <params>                  write the log to a file in plugins/LogBlock/log/ [rollback]",
			"# /lb savequeue                              write the queue to the database now [rollback]",
			"# /lb clearlog <params>                      delete log entries [clearlog]",
			"# /lb hide                                   stop logging yourself, type it again to start [hide]",
			"# /lb group                                  list the groups [groups]",
			"# /lb group add|remove <group> <player>      change the players of a group, saves this file [groups]",
			"# /lb group reload                           read the groups from this file again [groups]",
			"#",
			"# ===== Params =====  for lookup, tp, rollback, redo, writelogfile, clearlog and tool queries",
			"# player <name...>, block <type...>, created, destroyed, chestaccess, all, area <radius>, selection (WorldEdit),",
			"# world <name>, time <n> minutes|hours|days, since|before <dd.MM.yyyy> <HH:mm:ss>, limit <n>,",
			"# sum none|blocks|players, asc, desc, coords, silent, last (the params of your last command)",
			"# Example: /lb rollback player Griefer area 20 time 2 hours"};

	public static enum LogKillsLevel {
		PLAYERS, MONSTERS, ANIMALS
	}

	Config(LogBlock logblock) throws DataFormatException, IOException {
		final Map<String, Object> def = new HashMap<String, Object>();
		def.put("version", logblock.getDescription().getVersion());
		def.put("loggedWorlds", Arrays.asList("world", "world_nether"));
		def.put("mysql.host", "localhost");
		def.put("mysql.port", 3306);
		def.put("mysql.database", "minecraft");
		def.put("mysql.user", "username");
		def.put("mysql.password", "pass");
		def.put("consumer.delayBetweenRuns", 6);
		def.put("consumer.forceToProcessAtLeast", 20);
		def.put("consumer.timePerRun", 200);
		def.put("consumer.useBukkitScheduler", true);
		def.put("clearlog.dumpDeletedLog", false);
		def.put("clearlog.keepLogDays", -1);
		def.put("logging.logCreeperExplosionsAsPlayerWhoTriggeredThese", false);
		def.put("logging.logKillsLevel", "PLAYERS");
		def.put("logging.hiddenPlayers", new ArrayList<String>());
		def.put("logging.hiddenBlocks", Arrays.asList(0));
		def.put("rollback.dontRollback", Arrays.asList(10, 11, 46, 51));
		def.put("rollback.replaceAnyway", Arrays.asList(8, 9, 10, 11, 51));
		def.put("rollback.maxTime", "2 days");
		def.put("rollback.maxArea", 50);
		def.put("lookup.defaultDist", 20);
		def.put("lookup.defaultTime", "30 minutes");
		def.put("lookup.toolID", 270);
		def.put("lookup.toolblockID", 7);
		def.put("lookup.toolQuery", "area 0 all sum none limit 15 desc silent");
		def.put("lookup.toolBlockQuery", "area 0 all sum none limit 15 desc silent");
		def.put("lookup.linesPerPage", 15);
		def.put("lookup.linesLimit", 1500);
		// "questioner" is the old name from the LogBlockQuestioner plugin, kept so existing config.yml files still work.
		// Now these turn the /lb yes confirmations on or off, see docs/confirmations.md
		def.put("questioner.askRollbacks", true);
		def.put("questioner.askRedos", true);
		def.put("questioner.askClearLogs", true);
		def.put("questioner.askClearLogAfterRollback", true);
		// See docs/permissions.md. Added as a whole only if missing, so deleted groups don't come back
		def.put("permissions.usePermissionsPlugin", false);
		def.put("permissions.groups", PermissionGroups.defaults());
		final Configuration config = logblock.getConfiguration();
		config.load();
		for (final Entry<String, Object> e : def.entrySet())
			if (config.getProperty(e.getKey()) == null)
				config.setProperty(e.getKey(), e.getValue());
		config.setHeader(HEADER);
		if (!config.save())
			throw new IOException("Error while writing to config.yml");
		url = "jdbc:mysql://" + config.getString("mysql.host") + ":" + config.getString("mysql.port") + "/" + config.getString("mysql.database");
		user = config.getString("mysql.user");
		password = config.getString("mysql.password");
		delayBetweenRuns = config.getInt("consumer.delayBetweenRuns", 6);
		forceToProcessAtLeast = config.getInt("consumer.forceToProcessAtLeast", 0);
		timePerRun = config.getInt("consumer.timePerRun", 100);
		useBukkitScheduler = config.getBoolean("consumer.useBukkitScheduler", true);
		keepLogDays = config.getInt("clearlog.keepLogDays", -1);
		if (keepLogDays * 86400000L > System.currentTimeMillis())
			throw new DataFormatException("Too large timespan for keepLogDays. Must be shorter than " + (int)(System.currentTimeMillis() / 86400000L) + " days.");
		dumpDeletedLog = config.getBoolean("clearlog.dumpDeletedLog", false);
		logCreeperExplosionsAsPlayerWhoTriggeredThese = config.getBoolean("logging.logCreeperExplosionsAsPlayerWhoTriggeredThese", false);
		try {
			logKillsLevel = LogKillsLevel.valueOf(config.getString("logging.logKillsLevel"));
		} catch (final IllegalArgumentException ex) {
			throw new DataFormatException("lookup.toolblockID doesn't appear to be a valid log level. Allowed are 'PLAYERS', 'MONSTERS' and 'ANIMALS'");
		}
		hiddenPlayers = new HashSet<Integer>();
		for (final String playerName : config.getStringList("logging.hiddenPlayers", new ArrayList<String>()))
			hiddenPlayers.add(playerName.hashCode());
		hiddenBlocks = new HashSet<Integer>();
		for (final String blocktype : config.getStringList("logging.hiddenBlocks", new ArrayList<String>())) {
			final Material mat = Material.matchMaterial(blocktype);
			if (mat != null)
				hiddenBlocks.add(mat.getId());
			else
				throw new DataFormatException("Not a valid material: '" + blocktype + "'");
		}
		dontRollback = new HashSet<Integer>(config.getIntList("rollback.dontRollback", null));
		replaceAnyway = new HashSet<Integer>(config.getIntList("rollback.replaceAnyway", null));
		rollbackMaxTime = parseTimeSpec(config.getString("rollback.maxTime").split(" "));
		rollbackMaxArea = config.getInt("rollback.maxArea", 50);
		try {
			toolQuery = new QueryParams(logblock);
			toolQuery.prepareToolQuery = true;
			toolQuery.parseArgs(new ConsoleCommandSender(logblock.getServer()), Arrays.asList(config.getString("lookup.toolQuery").split(" ")));
		} catch (final IllegalArgumentException ex) {
			throw new DataFormatException("Error at lookup.toolQuery: " + ex.getMessage());
		}
		try {
			toolBlockQuery = new QueryParams(logblock);
			toolBlockQuery.prepareToolQuery = true;
			toolBlockQuery.parseArgs(new ConsoleCommandSender(logblock.getServer()), Arrays.asList(config.getString("lookup.toolBlockQuery").split(" ")));
		} catch (final IllegalArgumentException ex) {
			throw new DataFormatException("Error at lookup.toolBlockQuery: " + ex.getMessage());
		}
		defaultDist = config.getInt("lookup.defaultDist", 20);
		defaultTime = parseTimeSpec(config.getString("lookup.defaultTime").split(" "));
		toolID = config.getInt("lookup.toolID", 270);
		if (Material.getMaterial(toolID) == null || Material.getMaterial(toolID).isBlock())
			throw new DataFormatException("lookup.toolID doesn't appear to be a valid item id");
		toolblockID = config.getInt("lookup.toolblockID", 7);
		if (Material.getMaterial(toolblockID) == null || !Material.getMaterial(toolblockID).isBlock() || toolblockID == 0)
			throw new DataFormatException("lookup.toolblockID doesn't appear to be a valid block id");
		linesPerPage = config.getInt("lookup.linesPerPage", 15);
		linesLimit = config.getInt("lookup.linesLimit", 1500);
		askRollbacks = config.getBoolean("questioner.askRollbacks", true);
		askRedos = config.getBoolean("questioner.askRedos", true);
		askClearLogs = config.getBoolean("questioner.askClearLogs", true);
		askClearLogAfterRollback = config.getBoolean("questioner.askClearLogAfterRollback", true);
		usePermissionsPlugin = config.getBoolean("permissions.usePermissionsPlugin", false);
		permissionGroups = PermissionGroups.parse(config.getProperty("permissions.groups"));
		final List<String> worldNames = config.getStringList("loggedWorlds", null);
		worlds = new HashMap<Integer, WorldConfig>();
		if (worldNames == null || worldNames.size() == 0)
			throw new DataFormatException("No worlds configured");
		for (final String world : worldNames)
			worlds.put(world.hashCode(), new WorldConfig(new File("plugins/LogBlock/" + friendlyWorldname(world) + ".yml")));
		for (final WorldConfig wcfg : worlds.values()) {
			if (wcfg.logBlockPlacings)
				logBlockPlacings = true;
			if (wcfg.logBlockBreaks)
				logBlockBreaks = true;
			if (wcfg.logSignTexts)
				logSignTexts = true;
			if (wcfg.logExplosions)
				logExplosions = true;
			if (wcfg.logFire)
				logFire = true;
			if (wcfg.logLeavesDecay)
				logLeavesDecay = true;
			if (wcfg.logLavaFlow)
				logLavaFlow = true;
			if (wcfg.logWaterFlow)
				logWaterFlow = true;
			if (wcfg.logChestAccess)
				logChestAccess = true;
			if (wcfg.logButtonsAndLevers)
				logButtonsAndLevers = true;
			if (wcfg.logKills)
				logKills = true;
			if (wcfg.logChat)
				logChat = true;
			if (wcfg.logSnowForm)
				logSnowForm = true;
			if (wcfg.logSnowFade)
				logSnowFade = true;
		}
	}
}

class WorldConfig
{
	public final String table;
	public final boolean logBlockPlacings, logBlockBreaks, logSignTexts, logExplosions, logFire, logLeavesDecay, logLavaFlow, logWaterFlow, logChestAccess, logButtonsAndLevers, logKills, logChat, logSnowForm, logSnowFade;

	public WorldConfig(File file) {
		final Map<String, Object> def = new HashMap<String, Object>();
		def.put("table", "lb-" + file.getName().substring(0, file.getName().length() - 4));
		def.put("logBlockCreations", true);
		def.put("logBlockDestroyings", true);
		def.put("logSignTexts", true);
		def.put("logExplosions", true);
		def.put("logFire", true);
		def.put("logLeavesDecay", false);
		def.put("logLavaFlow", false);
		def.put("logWaterFlow", false);
		def.put("logChestAccess", false);
		def.put("logButtonsAndLevers", false);
		def.put("logKills", false);
		def.put("logChat", false);
		def.put("logSnowForm", false);
		def.put("logSnowFade", false);
		final Configuration config = new Configuration(file);
		config.load();
		for (final Entry<String, Object> e : def.entrySet())
			if (config.getProperty(e.getKey()) == null)
				config.setProperty(e.getKey(), e.getValue());
		config.save();
		table = config.getString("table");
		logBlockPlacings = config.getBoolean("logBlockCreations", true);
		logBlockBreaks = config.getBoolean("logBlockDestroyings", true);
		logSignTexts = config.getBoolean("logSignTexts", false);
		logExplosions = config.getBoolean("logExplosions", false);
		logFire = config.getBoolean("logFire", false);
		logLeavesDecay = config.getBoolean("logLeavesDecay", false);
		logLavaFlow = config.getBoolean("logLavaFlow", false);
		logWaterFlow = config.getBoolean("logWaterFlow", false);
		logChestAccess = config.getBoolean("logChestAccess", false);
		logButtonsAndLevers = config.getBoolean("logButtonsAndLevers", false);
		logKills = config.getBoolean("logKills", false);
		logChat = config.getBoolean("logChat", false);
		logSnowForm = config.getBoolean("logSnowForm", false);
		logSnowFade = config.getBoolean("logSnowFade", false);
	}
}
