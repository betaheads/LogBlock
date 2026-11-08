import java.io.File;
import java.util.*;

/**
 * End-to-end test of a fresh install: config.yml has only the MySQL settings, no permissions. Started by run.sh,
 * see tests/README.md.
 *
 * LogBlock has to add the default groups (PermissionGroups.defaults(), docs/permissions.md) to config.yml, and with
 * them only ops may use it. botA, an ordinary player, is denied every command that needs a permission, and the tool
 * and the tool block do nothing for botA. botB, an op (ops.txt), has every permission. The commands for everyone work
 * for botA too. At the end the console adds botA to moderator and the same clicks answer, so the silence before came
 * from the permissions and not from the clicks.
 *
 * Args: serverDir jdbcUrl user password. System property test.cp: classpath of the server.
 * The LogBlock tables of world "world" in the database are dropped before the test.
 * Uses TestServer, Bot, Steps and the helper plugin (/lbt: the platform at y=99 around 0,0, items) from tests/lib.
 */
public class FreshInstallTest extends Steps
{
	/** Some commands end it with a period, some don't */
	static final String DENIED = "You aren't allowed to do this";
	static final String NO_RESULTS = "No results found.";
	/** lookup.toolID and lookup.toolblockID of a new config.yml: wood pickaxe, bedrock */
	static final int TOOL = 270, TOOLBLOCK = 7;
	/** PermissionGroups.PERMISSIONS */
	static final String[] ALL = {"me", "lookup", "tool", "toolblock", "tp", "hide", "rollback", "clearlog", "ignoreRestrictions", "groups"};
	/** Every /lb command that needs a permission, aliases and the short lookup form included */
	static final String[] NEED_PERMISSION = {
		"/lb me", "/lb lookup area 5", "/lb player botB", "/lb area 5",
		"/lb tool", "/lb t", "/lb tool area 5", "/lb toolblock", "/lb tb", "/lb toolblock area 5",
		"/lb tp area 5", "/lb tp 1", "/lb hide",
		"/lb rollback area 5", "/lb rb area 5", "/lb undo area 5", "/lb redo area 5", "/lb writelogfile area 5", "/lb savequeue",
		"/lb clearlog area 5",
		"/lb group", "/lb groups", "/lb group add moderator botA", "/lb group remove moderator botA", "/lb group reload"};
	static final String[] TOOL_MODES = {"lookup", "rollback", "redo", "clearlog", "writelogfile"};
	/** The commands for everyone (docs/permissions.md) and a line of their answer. tool on comes last: the tool stays on. */
	static final String[][] FOR_EVERYONE = {
		{"/lb", "Type /lb help for help"},
		{"/lb help", "LogBlock Help:"},
		{"/lb commands", "LogBlock Commands:"},
		{"/lb params", "LogBlock Query Parameters:"},
		{"/lb permissions", "Your groups:"},
		{"/lb logging", "Currently logging in world:"},
		{"/lb page 1", "No blocks in lookup cache"},
		{"/lb next", "No blocks in lookup cache"},
		{"/lb prev", "No blocks in lookup cache"},
		{"/lb yes", "Nothing to confirm."},
		{"/lb no", "Nothing to cancel."},
		{"/lb tool off", "Tool disabled."},
		{"/lb tool on", "Tool enabled."},
		{"/lb toolblock off", "Tool block disabled."},
		{"/lb toolblock on", "Tool block enabled."}};

	public static void main(String[] args) throws Exception {
		final File dir = new File(args[0]);
		final File config = new File(dir, "plugins/LogBlock/config.yml");
		TestServer.dropTables(args[1], args[2], args[3]);

		final TestServer server = new TestServer(dir);
		try {
			final Bot a = new Bot("botA"), b = new Bot("botB");
			for (final Bot bot : new Bot[]{a, b}) {
				bot.connect();
				bot.waitPos();
			}
			server.cmd("lbt setup botA botB");
			server.waitFor("LBT setup done", 10);
			Thread.sleep(1500);

			step("F1 LogBlock added the default groups to config.yml, without warnings", () -> {
				check(server.linesWith("[LogBlock] config.yml:").isEmpty(), "warnings: " + server.linesWith("[LogBlock] config.yml:"));
				check(read(config).contains("usePermissionsPlugin: false"), "no usePermissionsPlugin: false in config.yml");
				// reads config.yml from disk
				final int mark = server.mark();
				server.cmd("lb group reload");
				server.waitFor(mark, "Reloaded 3 groups from config.yml.", 5);
			});
			step("F2 botB (op): /lb group lists the default groups", () -> {
				b.say("/lb group");
				b.expect("default (every player): no permissions");
				b.expect("moderator: lookup, tool, toolblock, tp, rollback");
				b.expect("admin (ops): *");
			});
			step("F3 botA (no op): no permissions, only in group default", () -> a.expectPermissions("default"));
			step("F4 botA: every command that needs a permission is denied", () -> {
				final List<String> allowed = new ArrayList<String>();
				for (final String command : NEED_PERMISSION)
					try {
						a.say(command, DENIED);
					} catch (final Exception ex) {
						allowed.add(command);
					}
				check(allowed.isEmpty(), "not denied: " + allowed);
			});
			step("F5 botA: every tool and tool block mode is denied", () -> {
				final List<String> allowed = new ArrayList<String>();
				for (final String tool : new String[]{"tool", "toolblock"})
					for (final String mode : TOOL_MODES)
						try {
							a.say("/lb " + tool + " mode " + mode, "You aren't allowed to use mode " + mode);
						} catch (final Exception ex) {
							allowed.add(tool + " mode " + mode);
						}
				check(allowed.isEmpty(), "not denied: " + allowed);
			});
			step("F6 botA: the commands for everyone work", () -> {
				final List<String> wrong = new ArrayList<String>();
				for (final String[] command : FOR_EVERYONE)
					try {
						a.say(command[0], command[1]);
					} catch (final Exception ex) {
						wrong.add(ex.getMessage());
					}
				check(wrong.isEmpty(), "wrong answers: " + wrong);
			});
			step("F7 botB (op): every permission, lookups, the tool and the tool block work", () -> {
				b.expectPermissions("default, admin", ALL);
				b.say("/lb lookup area 5", NO_RESULTS);
				b.say("/lb me", NO_RESULTS);
				b.say("/lb tool", "Here's your");
				rightClick(b, 1, 99, 1);
				b.expect(NO_RESULTS);
				b.say("/lb toolblock", "Here's your");
				rightClick(b, -1, 99, -1);
				b.expect(NO_RESULTS);
			});
			step("F8 botA: right click with the wood pickaxe and with bedrock, no answer", () -> {
				give(server, "botA", TOOL);
				rightClick(a, 1, 99, 1);
				a.expectNothing(2000);
				// without the permission the bedrock is an ordinary block and may be placed on 1,100,-1
				give(server, "botA", TOOLBLOCK);
				rightClick(a, 1, 99, -1);
				a.expectNothing(2000);
			});
			step("F9 console: lb group add moderator botA, now botA's tool and tool block answer", () -> {
				final int mark = server.mark();
				server.cmd("lb group add moderator botA");
				server.waitFor(mark, "Added botA to group moderator.", 5);
				check(read(config).contains("- botA"), "botA not in config.yml");
				a.expectPermissions("default, moderator", "lookup", "tool", "toolblock", "tp", "rollback");
				give(server, "botA", TOOL);
				rightClick(a, 1, 99, 1);
				a.expect(NO_RESULTS);
				give(server, "botA", TOOLBLOCK);
				rightClick(a, -1, 99, -1);
				a.expect(NO_RESULTS);
				a.say("/lb clearlog area 5", DENIED);
				a.say("/lb group", DENIED);
			});
			for (final Bot bot : new Bot[]{a, b})
				bot.disconnect();
			Thread.sleep(500);
		} catch (final Exception ex) {
			ex.printStackTrace(System.out);
			failures++;
		}
		if (!server.stop())
			failures++;
		server.printProblems();
		finish(server);
	}

	/** Puts one item into the hotbar slot 0 of the player, the one the bot holds */
	static void give(TestServer server, String player, int id) throws Exception {
		final int mark = server.mark();
		server.cmd("lbt give " + player + " " + id);
		server.waitFor(mark, "LBT give done", 5);
	}

	/** Right click on the top face of the block with the held item, after the answers to earlier commands have come */
	static void rightClick(Bot bot, int x, int y, int z) throws Exception {
		Thread.sleep(500);
		bot.chat.clear();
		bot.place(x, y, z);
	}
}
