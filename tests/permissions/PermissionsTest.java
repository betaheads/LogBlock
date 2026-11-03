import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * End-to-end test of the permission groups in config.yml (docs/permissions.md). Started by run.sh, see tests/README.md.
 *
 * run.sh writes the groups below into config.yml; botC is in ops.txt. The test connects the bots, which type /lb
 * commands into the chat, types /lb group commands into the server console, edits config.yml and checks the answers.
 *   default (default: true): me
 *   moderator: logblock.lookup, tool, TP; players: BotA
 *   admin (ops: true): '*'
 *   typo: lokup (unknown permission, a warning at startup)
 *
 * Args: serverDir jdbcUrl user password. System property test.cp: classpath of the server.
 * The LogBlock tables of world "world" in the database are dropped before the test.
 * Uses TestServer and Bot from tests/lib.
 */
public class PermissionsTest
{
	/** Some commands end it with a period, some don't */
	static final String DENIED = "You aren't allowed to do this";
	static final String LOOKUP_DONE = "No results found.";
	static int failures = 0;

	interface Step
	{
		void run() throws Exception;
	}

	/** Runs one step; a failed step is counted and the test goes on with the next one */
	static void step(String name, Step step) {
		System.out.println(name);
		try {
			step.run();
		} catch (final Exception ex) {
			System.out.println("  FAIL: " + ex.getMessage());
			failures++;
		}
	}

	static void check(boolean ok, String message) throws Exception {
		if (!ok)
			throw new Exception(message);
	}

	public static void main(String[] args) throws Exception {
		final File dir = new File(args[0]);
		final File config = new File(dir, "plugins/LogBlock/config.yml");
		TestServer.dropTables(args[1], args[2], args[3]);

		final TestServer server = new TestServer(dir);
		try {
			final Bot a = new Bot("botA"), b = new Bot("botB"), c = new Bot("botC");
			for (final Bot bot : new Bot[]{a, b, c}) {
				bot.connect();
				bot.waitPos();
			}

			step("P1 unknown permission in config.yml: warning at startup, the plugin works", () ->
				server.waitFor("Group 'typo': unknown permission 'lokup'", 1));
			step("P2 botA is in moderator by name (BotA in config.yml), plus default", () ->
				expectPermissions(a, "default, moderator", "me", "lookup", "tool", "tp"));
			step("P3 botA: lookup allowed", () -> a.say("/lb lookup area 5", LOOKUP_DONE));
			step("P4 botA: rollback denied", () -> a.say("/lb rollback area 5", DENIED));
			step("P5 botA: tool mode rollback denied", () -> a.say("/lb tool mode rollback", "You aren't allowed to use mode rollback"));
			step("P6 botB is only in default: me allowed, lookup denied", () -> {
				expectPermissions(b, "default", "me");
				b.say("/lb me", LOOKUP_DONE);
				b.say("/lb lookup area 5", DENIED);
			});
			step("P7 botB: /lb group denied", () -> {
				b.say("/lb group add moderator botB", DENIED);
				b.say("/lb group", DENIED);
			});
			step("P8 botC is an op, so in admin with '*'", () ->
				expectPermissions(c, "default, admin", "me", "lookup", "tool", "toolblock", "tp", "hide", "rollback", "clearlog", "ignoreRestrictions", "groups"));
			step("P9 botC: /lb group lists the groups", () -> {
				c.say("/lb group");
				c.expect("default (every player): me");
				c.expect("moderator: lookup, tool, tp");
				c.expect("players: BotA");
				c.expect("admin (ops): *");
				c.expect("typo: no permissions");
			});
			step("P10 console: lb group add moderator botB, saved in config.yml", () -> {
				final int mark = server.mark();
				server.cmd("lb group add moderator botB");
				server.waitFor(mark, "Added botB to group moderator.", 5);
				check(read(config).contains("- botB"), "botB not in config.yml");
				b.say("/lb lookup area 5", LOOKUP_DONE);
			});
			step("P11 botC: /lb group remove moderator BOTB (case doesn't matter)", () -> {
				c.say("/lb group remove moderator BOTB", "Removed BOTB from group moderator.");
				check(!read(config).toLowerCase().contains("botb"), "botB still in config.yml");
				b.say("/lb lookup area 5", DENIED);
			});
			step("P12 mistakes in /lb group", () -> {
				// The server wraps chat lines wider than the chat window, so only the start of longer messages is checked
				c.say("/lb group add nosuchgroup botB", "There is no group 'nosuchgroup'.");
				c.say("/lb group add moderator botA", "botA is in group moderator already");
				c.say("/lb group remove moderator botB", "botB isn't in the players of group moderator");
				c.say("/lb group add moderator", "Usage: /lb group");
			});
			step("P13 config.yml edited by hand, then lb group reload", () -> {
				final String text = read(config);
				check(text.startsWith("# LogBlock config"), "config.yml has no header");
				write(config, replacePermissions(text, "permissions:\n"
						+ "    groups:\n"
						+ "        default:\n"
						+ "            default: true\n"
						+ "            permissions: []\n"
						+ "        helper:\n"
						+ "            permissions: [lookup]\n"
						+ "            players: [botB]\n"));
				final int mark = server.mark();
				server.cmd("lb group reload");
				server.waitFor(mark, "Reloaded 2 groups from config.yml.", 5);
				b.say("/lb lookup area 5", LOOKUP_DONE);
				b.say("/lb me", DENIED);
				a.say("/lb lookup area 5", DENIED);
				// with no groups permission left, botC can't change the groups any more
				c.say("/lb group", DENIED);
			});
			step("P14 config.yml broken: reload and add refused, old groups and the file stay", () -> {
				final String broken = read(config).replace("permissions: [lookup]", "permissions: [lookup");
				write(config, broken);
				int mark = server.mark();
				server.cmd("lb group reload");
				server.waitFor(mark, "config.yml has a mistake (see server log), nothing changed", 5);
				mark = server.mark();
				server.cmd("lb group add helper botA");
				server.waitFor(mark, "config.yml has a mistake (see server log), nothing changed", 5);
				check(read(config).equals(broken), "config.yml was changed");
				b.say("/lb lookup area 5", LOOKUP_DONE);
			});
			for (final Bot bot : new Bot[]{a, b, c})
				bot.disconnect();
			Thread.sleep(500);
		} catch (final Exception ex) {
			ex.printStackTrace(System.out);
			failures++;
		}
		if (!server.stop())
			failures++;
		server.printProblems("unknown permission 'lokup'", "Can't read config.yml", "snakeyaml");
		System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED (server log: " + server.logFile() + ")");
		System.exit(failures == 0 ? 0 : 1);
	}

	/** /lb permissions: exactly these permissions (without "logblock.") and "Your groups: groups" */
	static void expectPermissions(Bot bot, String groups, String... permissions) throws Exception {
		bot.say("/lb permissions");
		final List<String> lines = bot.collect("Your groups:");
		final List<String> actual = new ArrayList<String>();
		for (final String line : lines)
			if (line.startsWith("logblock."))
				actual.add(line.substring("logblock.".length()));
		check(actual.equals(Arrays.asList(permissions)), "permissions " + actual + ", expected " + Arrays.asList(permissions));
		check(lines.get(lines.size() - 1).equals("Your groups: " + groups), lines.get(lines.size() - 1) + ", expected groups " + groups);
	}

	static String read(File file) throws Exception {
		return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
	}

	static void write(File file, String text) throws Exception {
		Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
	}

	/** Replaces the top-level permissions: section, as written by the plugin, with block */
	static String replacePermissions(String text, String block) throws Exception {
		final StringBuilder sb = new StringBuilder();
		boolean inside = false, found = false;
		for (final String line : text.split("\r?\n")) {
			if (line.equals("permissions:")) {
				inside = found = true;
				continue;
			}
			if (inside && (line.startsWith(" ") || line.isEmpty()))
				continue;
			inside = false;
			sb.append(line).append('\n');
		}
		check(found, "no permissions: in config.yml");
		return sb.append(block).toString();
	}
}
