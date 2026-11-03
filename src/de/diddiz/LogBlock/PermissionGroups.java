package de.diddiz.LogBlock;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Who may use which /lb command: the groups in permissions.groups of config.yml, see docs/permissions.md.
 *
 * A player is in a group if the group has default: true (every player), or ops: true and the player is an op,
 * or the player's name is in the group's players list (case doesn't matter). A player has a permission if one of
 * their groups gives it.
 *
 * Immutable: a reload builds a new object, so async commands can call has() at any time.
 * Plain Java without Bukkit, so tests/unit can test it without a server.
 */
public class PermissionGroups
{
	/** All LogBlock permissions, as written in config.yml: without "logblock." */
	public static final List<String> PERMISSIONS = Collections.unmodifiableList(Arrays.asList("me", "lookup", "tool", "toolblock", "tp", "hide", "rollback", "clearlog", "ignoreRestrictions", "groups"));
	/** In a group's permissions: gives all of PERMISSIONS. "all" means the same. */
	public static final String ALL = "*";
	private static final List<String> GROUP_SETTINGS = Arrays.asList("default", "ops", "permissions", "players");

	private final List<Group> groups;
	private final List<String> warnings;

	public static class Group
	{
		public final String name;
		/** default: true, every player is in the group */
		public final boolean everyone;
		/** ops: true, every op is in the group */
		public final boolean ops;
		/** Names from PERMISSIONS or ALL, in config order */
		public final Set<String> permissions;
		/** As written in config.yml */
		public final List<String> players;
		private final Set<String> lowerCasePlayers = new HashSet<String>();

		Group(String name, boolean everyone, boolean ops, Set<String> permissions, List<String> players) {
			this.name = name;
			this.everyone = everyone;
			this.ops = ops;
			this.permissions = Collections.unmodifiableSet(permissions);
			this.players = Collections.unmodifiableList(players);
			for (final String player : players)
				lowerCasePlayers.add(player.toLowerCase());
		}

		public boolean contains(String player, boolean op) {
			return everyone || ops && op || lowerCasePlayers.contains(player.toLowerCase());
		}

		/** @param permission a name from PERMISSIONS */
		public boolean gives(String permission) {
			return permissions.contains(ALL) || permissions.contains(permission);
		}
	}

	private PermissionGroups(List<Group> groups, List<String> warnings) {
		this.groups = Collections.unmodifiableList(groups);
		this.warnings = Collections.unmodifiableList(warnings);
	}

	/**
	 * Never fails: a mistake in one group is skipped and reported in getWarnings(), so a typo in the permissions
	 * can't stop the logging.
	 * @param node the value of permissions.groups as loaded from config.yml: group name -> settings
	 */
	public static PermissionGroups parse(Object node) {
		final List<Group> groups = new ArrayList<Group>();
		final List<String> warnings = new ArrayList<String>();
		if (!(node instanceof Map)) {
			warnings.add("permissions.groups is missing or has no groups below it: only the console can use LogBlock commands");
			return new PermissionGroups(groups, warnings);
		}
		final Set<String> names = new HashSet<String>();
		for (final Map.Entry<?, ?> entry : ((Map<?, ?>)node).entrySet()) {
			final String name = String.valueOf(entry.getKey());
			if (!names.add(name.toLowerCase())) {
				warnings.add("Group '" + name + "' is there twice (case doesn't matter), the second one is ignored");
				continue;
			}
			final Object value = entry.getValue();
			if (value != null && !(value instanceof Map)) {
				warnings.add("Group '" + name + "' is ignored: put default, ops, permissions and players below it");
				continue;
			}
			final Map<?, ?> settings = value == null ? Collections.emptyMap() : (Map<?, ?>)value;
			for (final Object key : settings.keySet())
				if (!GROUP_SETTINGS.contains(String.valueOf(key)))
					warnings.add("Group '" + name + "': unknown setting '" + key + "', allowed are " + join(GROUP_SETTINGS));
			final Set<String> permissions = new LinkedHashSet<String>();
			for (final String permission : toStrings(settings.get("permissions"))) {
				final String normalized = normalize(permission);
				if (normalized != null)
					permissions.add(normalized);
				else
					warnings.add("Group '" + name + "': unknown permission '" + permission + "', allowed are " + join(PERMISSIONS) + ", " + ALL);
			}
			groups.add(new Group(name, flag(settings, "default", name, warnings), flag(settings, "ops", name, warnings), permissions, toStrings(settings.get("players"))));
		}
		return new PermissionGroups(groups, warnings);
	}

	/** Group settings, in config order */
	public List<Group> getGroups() {
		return groups;
	}

	/** Mistakes in config.yml found by parse */
	public List<String> getWarnings() {
		return warnings;
	}

	/** Case doesn't matter. Null if there is none. */
	public Group getGroup(String name) {
		for (final Group group : groups)
			if (group.name.equalsIgnoreCase(name))
				return group;
		return null;
	}

	public List<Group> groupsOf(String player, boolean op) {
		final List<Group> result = new ArrayList<Group>();
		for (final Group group : groups)
			if (group.contains(player, op))
				result.add(group);
		return result;
	}

	/** @param permission e.g. "logblock.lookup" or "lookup" */
	public boolean has(String player, boolean op, String permission) {
		final String normalized = normalize(permission);
		if (normalized == null || normalized.equals(ALL))
			return false;
		for (final Group group : groups)
			if (group.contains(player, op) && group.gives(normalized))
				return true;
		return false;
	}

	/**
	 * "logblock.lookup", "Lookup", "lookup" -> "lookup"; "*", "all", "logblock.*" -> ALL.
	 * @return null if it's not a LogBlock permission
	 */
	static String normalize(String permission) {
		String name = permission.trim();
		if (name.toLowerCase().startsWith("logblock."))
			name = name.substring("logblock.".length());
		if (name.equals(ALL) || name.equalsIgnoreCase("all"))
			return ALL;
		for (final String known : PERMISSIONS)
			if (known.equalsIgnoreCase(name))
				return known;
		return null;
	}

	/** permissions.groups for a new config.yml. Ordinary players get nothing, default is there to show where to add it. */
	static Map<String, Object> defaults() {
		final Map<String, Object> groups = new LinkedHashMap<String, Object>();
		groups.put("default", group(true, false));
		groups.put("moderator", group(false, false, "lookup", "tool", "toolblock", "tp", "rollback"));
		groups.put("admin", group(false, true, ALL));
		return groups;
	}

	private static Map<String, Object> group(boolean everyone, boolean ops, String... permissions) {
		final Map<String, Object> group = new LinkedHashMap<String, Object>();
		if (everyone)
			group.put("default", true);
		if (ops)
			group.put("ops", true);
		group.put("permissions", new ArrayList<String>(Arrays.asList(permissions)));
		group.put("players", new ArrayList<String>());
		return group;
	}

	/**
	 * Adds the player to the players list of the group, for /lb group add. Changes node, the caller saves it.
	 * @param node the value of permissions.groups as loaded from config.yml
	 * @return the group's name as written in config.yml
	 * @throws IllegalArgumentException no such group, or the player is in its list already
	 */
	static String addPlayer(Object node, String group, String player) {
		return changePlayers(node, group, player, true);
	}

	/** Like addPlayer, for /lb group remove. Case doesn't matter, so "STEVE" removes "Steve". */
	static String removePlayer(Object node, String group, String player) {
		return changePlayers(node, group, player, false);
	}

	@SuppressWarnings("unchecked")
	private static String changePlayers(Object node, String group, String player, boolean add) {
		if (!(node instanceof Map))
			throw new IllegalArgumentException("config.yml has no permissions.groups");
		final Map<Object, Object> groups = (Map<Object, Object>)node;
		Object key = null;
		for (final Object name : groups.keySet())
			if (String.valueOf(name).equalsIgnoreCase(group)) {
				key = name;
				break;
			}
		if (key == null)
			throw new IllegalArgumentException("There is no group '" + group + "'. Groups: " + join(groups.keySet()));
		final String name = String.valueOf(key);
		Object settings = groups.get(key);
		if (settings == null) {
			settings = new LinkedHashMap<Object, Object>();
			groups.put(key, settings);
		} else if (!(settings instanceof Map))
			throw new IllegalArgumentException("Group '" + name + "' is broken in config.yml, fix it there");
		final List<String> players = toStrings(((Map<Object, Object>)settings).get("players"));
		boolean found = false;
		for (final Iterator<String> it = players.iterator(); it.hasNext();)
			if (it.next().equalsIgnoreCase(player)) {
				found = true;
				if (!add)
					it.remove();
			}
		if (add && found)
			throw new IllegalArgumentException(player + " is in group " + name + " already");
		if (!add && !found)
			throw new IllegalArgumentException(player + " isn't in the players of group " + name);
		if (add)
			players.add(player);
		((Map<Object, Object>)settings).put("players", players);
		return name;
	}

	private static boolean flag(Map<?, ?> settings, String key, String group, List<String> warnings) {
		final Object value = settings.get(key);
		if (value == null || value instanceof Boolean)
			return Boolean.TRUE.equals(value);
		warnings.add("Group '" + group + "': " + key + " must be true or false, not '" + value + "'");
		return false;
	}

	/** A list, or a single value as a list of one. YAML reads names like 123 as numbers, they become strings again. */
	private static List<String> toStrings(Object value) {
		final List<String> result = new ArrayList<String>();
		for (final Object element : value instanceof List ? (List<?>)value : value == null ? Collections.emptyList() : Collections.singletonList(value))
			if (element != null && String.valueOf(element).trim().length() > 0)
				result.add(String.valueOf(element).trim());
		return result;
	}

	static String join(Iterable<?> values) {
		final StringBuilder sb = new StringBuilder();
		for (final Object value : values) {
			if (sb.length() > 0)
				sb.append(", ");
			sb.append(value);
		}
		return sb.toString();
	}
}
