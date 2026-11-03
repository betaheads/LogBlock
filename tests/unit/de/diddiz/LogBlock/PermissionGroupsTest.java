package de.diddiz.LogBlock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.util.config.Configuration;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** PermissionGroups, see docs/permissions.md. The YAML tests use Bukkit's Configuration from server.jar, like the plugin. */
public class PermissionGroupsTest
{
	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	private static Map<String, Object> group(Object... keysAndValues) {
		final Map<String, Object> group = new LinkedHashMap<String, Object>();
		for (int i = 0; i < keysAndValues.length; i += 2)
			group.put((String)keysAndValues[i], keysAndValues[i + 1]);
		return group;
	}

	private static List<String> list(String... values) {
		return new ArrayList<String>(Arrays.asList(values));
	}

	private static List<String> names(List<PermissionGroups.Group> groups) {
		final List<String> names = new ArrayList<String>();
		for (final PermissionGroups.Group group : groups)
			names.add(group.name);
		return names;
	}

	/** default: player, moderator: Steve and Alex, admin: ops and Notch */
	private static Map<String, Object> sample() {
		final Map<String, Object> groups = new LinkedHashMap<String, Object>();
		groups.put("player", group("default", true, "permissions", list("me")));
		groups.put("moderator", group("permissions", list("lookup", "tool", "tp"), "players", list("Steve", "Alex")));
		groups.put("admin", group("ops", true, "permissions", list("*"), "players", list("Notch")));
		return groups;
	}

	@Test
	public void defaultGroupHasEveryPlayer() {
		final PermissionGroups groups = PermissionGroups.parse(sample());
		assertTrue(groups.has("SomeNewPlayer", false, "logblock.me"));
		assertFalse(groups.has("SomeNewPlayer", false, "logblock.lookup"));
		assertEquals(Arrays.asList("player"), names(groups.groupsOf("SomeNewPlayer", false)));
	}

	@Test
	public void playersByNameCaseDoesNotMatter() {
		final PermissionGroups groups = PermissionGroups.parse(sample());
		assertTrue(groups.has("Steve", false, "logblock.lookup"));
		assertTrue(groups.has("steve", false, "logblock.lookup"));
		assertTrue(groups.has("ALEX", false, "logblock.tp"));
		assertFalse(groups.has("Steve", false, "logblock.rollback"));
		assertFalse(groups.has("Steven", false, "logblock.lookup"));
	}

	@Test
	public void permissionsOfAllGroupsAddUp() {
		final PermissionGroups groups = PermissionGroups.parse(sample());
		assertTrue(groups.has("Steve", false, "logblock.me"));
		assertEquals(Arrays.asList("player", "moderator"), names(groups.groupsOf("Steve", false)));
	}

	@Test
	public void opsGroupHasOpsOnly() {
		final PermissionGroups groups = PermissionGroups.parse(sample());
		assertTrue(groups.has("AnyOp", true, "logblock.rollback"));
		assertFalse(groups.has("AnyOp", false, "logblock.rollback"));
		assertTrue(groups.has("Notch", false, "logblock.rollback"));
		assertEquals(Arrays.asList("player", "admin"), names(groups.groupsOf("AnyOp", true)));
	}

	@Test
	public void starGivesEveryPermission() {
		final PermissionGroups groups = PermissionGroups.parse(sample());
		for (final String permission : PermissionGroups.PERMISSIONS) {
			assertTrue(permission, groups.has("Notch", false, "logblock." + permission));
			assertTrue(permission, groups.has("Notch", false, permission));
		}
		assertFalse(groups.has("Notch", false, "logblock.unknown"));
		assertFalse(groups.has("Notch", false, "worldedit.lookup"));
	}

	@Test
	public void permissionNamesInConfig() {
		assertEquals("lookup", PermissionGroups.normalize("lookup"));
		assertEquals("lookup", PermissionGroups.normalize("logblock.lookup"));
		assertEquals("lookup", PermissionGroups.normalize(" LogBlock.LOOKUP "));
		assertEquals("ignoreRestrictions", PermissionGroups.normalize("ignorerestrictions"));
		assertEquals(PermissionGroups.ALL, PermissionGroups.normalize("*"));
		assertEquals(PermissionGroups.ALL, PermissionGroups.normalize("logblock.*"));
		assertEquals(PermissionGroups.ALL, PermissionGroups.normalize("ALL"));
		assertNull(PermissionGroups.normalize("lokup"));
		assertNull(PermissionGroups.normalize("worldedit.*"));
	}

	@Test
	public void allIsTheSameAsStar() {
		final Map<String, Object> node = new LinkedHashMap<String, Object>();
		node.put("admin", group("permissions", list("all"), "players", list("Notch")));
		assertTrue(PermissionGroups.parse(node).has("Notch", false, "logblock.clearlog"));
	}

	@Test
	public void singleValuesAndNumbers() {
		final Map<String, Object> node = new LinkedHashMap<String, Object>();
		// permissions: lookup / players: 1234 instead of lists; YAML reads 1234 as a number
		node.put("helper", group("permissions", "lookup", "players", 1234));
		final PermissionGroups groups = PermissionGroups.parse(node);
		assertTrue(groups.has("1234", false, "lookup"));
		assertTrue(groups.getWarnings().isEmpty());
	}

	@Test
	public void emptyGroupGivesNothing() {
		final Map<String, Object> node = new LinkedHashMap<String, Object>();
		node.put("empty", null);
		final PermissionGroups groups = PermissionGroups.parse(node);
		assertEquals(1, groups.getGroups().size());
		assertFalse(groups.has("Steve", true, "me"));
		assertTrue(groups.getWarnings().isEmpty());
	}

	@Test
	public void mistakesAreSkippedWithWarnings() {
		final Map<String, Object> node = new LinkedHashMap<String, Object>();
		node.put("typo", group("permissions", list("lokup", "tp"), "player", list("Steve"), "default", "yes please"));
		node.put("notamap", "lookup");
		node.put("Typo", group("permissions", list("*")));
		final PermissionGroups groups = PermissionGroups.parse(node);
		assertEquals(Arrays.asList("typo"), names(groups.getGroups()));
		// the rest of the group still works
		final PermissionGroups.Group typo = groups.getGroup("TYPO");
		assertEquals(Arrays.asList("tp"), new ArrayList<String>(typo.permissions));
		assertFalse(typo.everyone);
		assertTrue(typo.players.isEmpty());
		final String warnings = groups.getWarnings().toString();
		assertEquals(warnings, 5, groups.getWarnings().size());
		assertTrue(warnings, warnings.contains("unknown permission 'lokup'"));
		assertTrue(warnings, warnings.contains("unknown setting 'player'"));
		assertTrue(warnings, warnings.contains("default must be true or false"));
		assertTrue(warnings, warnings.contains("Group 'notamap' is ignored"));
		assertTrue(warnings, warnings.contains("Group 'Typo' is there twice"));
	}

	@Test
	public void noGroupsMeansOnlyTheConsole() {
		for (final Object node : new Object[]{null, "nonsense", list("a")}) {
			final PermissionGroups groups = PermissionGroups.parse(node);
			assertTrue(groups.getGroups().isEmpty());
			assertFalse(groups.has("Notch", true, "me"));
			assertEquals(1, groups.getWarnings().size());
		}
	}

	@Test
	public void defaults() {
		final PermissionGroups groups = PermissionGroups.parse(PermissionGroups.defaults());
		assertTrue(groups.getWarnings().isEmpty());
		assertEquals(Arrays.asList("default", "moderator", "admin"), names(groups.getGroups()));
		// an ordinary player gets nothing
		assertTrue(groups.getGroup("default").everyone);
		assertTrue(groups.getGroup("default").permissions.isEmpty());
		for (final String permission : PermissionGroups.PERMISSIONS)
			assertFalse(permission, groups.has("Anyone", false, permission));
		assertTrue(groups.has("AnyOp", true, "clearlog"));
		assertTrue(groups.getGroup("moderator").players.isEmpty());
		assertTrue(groups.getGroup("moderator").gives("rollback"));
		assertFalse(groups.getGroup("moderator").gives("ignoreRestrictions"));
	}

	@Test
	public void addAndRemovePlayers() {
		final Map<String, Object> node = sample();
		assertEquals("moderator", PermissionGroups.addPlayer(node, "MODERATOR", "Bob"));
		assertTrue(PermissionGroups.parse(node).has("bob", false, "lookup"));
		assertEquals("moderator", PermissionGroups.removePlayer(node, "moderator", "STEVE"));
		final PermissionGroups groups = PermissionGroups.parse(node);
		assertFalse(groups.has("Steve", false, "lookup"));
		assertEquals(Arrays.asList("Alex", "Bob"), groups.getGroup("moderator").players);
		// a group without players list yet, and an empty group
		assertEquals("player", PermissionGroups.addPlayer(node, "player", "Bob"));
		node.put("empty", null);
		PermissionGroups.addPlayer(node, "empty", "Bob");
		assertEquals(Arrays.asList("Bob"), PermissionGroups.parse(node).getGroup("empty").players);
	}

	@Test
	public void addAndRemoveMistakes() {
		final Map<String, Object> node = sample();
		expectError("There is no group 'mod'. Groups: player, moderator, admin", node, "mod", "Bob", true);
		expectError("steve is in group moderator already", node, "moderator", "steve", true);
		expectError("Bob isn't in the players of group moderator", node, "moderator", "Bob", false);
		node.put("broken", "lookup");
		expectError("Group 'broken' is broken in config.yml, fix it there", node, "broken", "Bob", true);
		assertEquals(sample(), withoutBroken(node));
	}

	private static Map<String, Object> withoutBroken(Map<String, Object> node) {
		node.remove("broken");
		return node;
	}

	private static void expectError(String message, Object node, String group, String player, boolean add) {
		try {
			if (add)
				PermissionGroups.addPlayer(node, group, player);
			else
				PermissionGroups.removePlayer(node, group, player);
			fail("no error, expected: " + message);
		} catch (final IllegalArgumentException ex) {
			assertEquals(message, ex.getMessage());
		}
	}

	/** Like Config does for a new config.yml, and LogBlock.changeGroup for /lb group add */
	@Test
	public void defaultsAndAddedPlayersSurviveConfigYml() throws Exception {
		final File file = new File(tmp.getRoot(), "config.yml");
		Configuration config = new Configuration(file);
		config.load();
		config.setProperty("permissions.groups", PermissionGroups.defaults());
		config.setHeader(Config.HEADER);
		assertTrue(config.save());

		config = new Configuration(file);
		config.load();
		final Object node = config.getProperty("permissions.groups");
		assertEquals(PermissionGroups.defaults(), node);
		// names YAML would read as something else if they weren't quoted
		for (final String name : new String[]{"yes", "No", "123", "007", "null", "~", "Steve"})
			PermissionGroups.addPlayer(node, "moderator", name);
		config.setHeader(Config.HEADER);
		assertTrue(config.save());

		config = new Configuration(file);
		config.load();
		final PermissionGroups groups = PermissionGroups.parse(config.getProperty("permissions.groups"));
		assertTrue(groups.getWarnings().isEmpty());
		assertEquals(Arrays.asList("yes", "No", "123", "007", "null", "~", "Steve"), groups.getGroup("moderator").players);
		final String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
		assertTrue(text, text.startsWith("# LogBlock config"));
		assertTrue(text, text.contains("'*'"));
	}

	/** A header line without # would break the YAML of config.yml */
	@Test
	public void headerIsOnlyComments() throws Exception {
		for (final String line : Config.HEADER) {
			assertTrue(line, line.startsWith("#"));
			assertTrue(line.length() + " chars: " + line, line.length() <= 120);
		}
		final File file = new File(tmp.getRoot(), "config.yml");
		Configuration config = new Configuration(file);
		config.setProperty("permissions.groups", PermissionGroups.defaults());
		config.setHeader(Config.HEADER);
		assertTrue(config.save());
		config = new Configuration(file);
		config.load();
		assertEquals(PermissionGroups.defaults(), config.getProperty("permissions.groups"));
	}

	/** config.yml as an admin would write it by hand */
	@Test
	public void handWrittenConfigYml() throws Exception {
		final File file = new File(tmp.getRoot(), "config.yml");
		final Writer w = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8);
		w.write("permissions:\n"
				+ "    groups:\n"
				+ "        default:\n"
				+ "            default: true\n"
				+ "            permissions: [me, logblock.tool]\n"
				+ "        moderator:\n"
				+ "            permissions:\n"
				+ "            - lookup\n"
				+ "            - TP\n"
				+ "            players:\n"
				+ "            - Steve\n"
				+ "            - alex\n"
				+ "        admin:\n"
				+ "            ops: true\n"
				+ "            permissions: all\n"
				+ "            players: Notch\n");
		w.close();
		final Configuration config = new Configuration(file);
		config.load();
		final PermissionGroups groups = PermissionGroups.parse(config.getProperty("permissions.groups"));
		assertTrue(groups.getWarnings().toString(), groups.getWarnings().isEmpty());
		assertTrue(groups.has("Bob", false, "tool"));
		assertTrue(groups.has("Alex", false, "tp"));
		assertFalse(groups.has("Alex", false, "rollback"));
		assertTrue(groups.has("notch", false, "rollback"));
		assertTrue(groups.has("Op", true, "groups"));
	}
}
