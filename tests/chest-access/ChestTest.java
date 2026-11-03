import java.io.File;
import java.sql.*;
import java.util.*;

/**
 * End-to-end test of chest access logging (LBChestAccessListener). Started by run.sh, see tests/README.md.
 *
 * Starts the Poseidon server in serverDir, connects two bots that speak the b1.7.3 protocol (version 14),
 * builds the scene with the LBTest helper plugin (/lbt setup), opens containers, moves items, stops the server
 * and compares the rows in the -chest table with the expected ones.
 *
 * Args: serverDir jdbcUrl user password. System property test.cp: classpath of the server.
 * The LogBlock tables of world "world" in the database are dropped before the test.
 * Uses TestServer and Bot from tests/lib.
 */
public class ChestTest
{
	public static void main(String[] args) throws Exception {
		final File dir = new File(args[0]);
		final String jdbc = args[1], user = args[2], password = args[3];
		TestServer.dropTables(jdbc, user, password);

		final TestServer server = new TestServer(dir);
		int failures = 0;
		try {
			final Bot a = new Bot("botA"), b = new Bot("botB");
			a.connect();
			b.connect();
			a.waitPos();
			b.waitPos();
			server.cmd("lbt setup botA botB");
			server.waitFor("LBT setup done", 10);
			Thread.sleep(1500);

			// Window slots: single chest 0-26 chest, 27-53 player inventory, 54-62 hotbar;
			// double chest 0-53 chest, 54-80 inventory, 81-89 hotbar; dispenser 0-8, 9-35, 36-44.
			System.out.println("T1 single chest: take 10 stone, put 20 glass");
			int w = a.open(2, 100, 0);
			a.click(w, 0); a.click(w, 30);   // stone -> player slot 12
			a.click(w, 54); a.click(w, 5);   // glass (hotbar 0) -> chest slot 5
			a.close(w);
			System.out.println("T2 double chest: take from both halves");
			w = a.open(-2, 100, 0);
			a.click(w, 0); a.click(w, 60);   // cobblestone (D1) -> player slot 15
			a.click(w, 27); a.click(w, 61);  // planks (D2) -> player slot 16
			a.close(w);
			System.out.println("T3 chest with a block above: doesn't open, nothing logged");
			a.tryOpenExpectFail(0, 100, 3);
			System.out.println("T4 two players at one chest: each change logged once");
			w = a.open(2, 100, 0);
			final int wb = b.open(2, 100, 0);
			a.click(w, 1); a.click(w, 35);   // dirt -> player slot 17
			a.close(w);
			Thread.sleep(300);
			b.click(wb, 5); b.click(wb, 30); // glass -> botB player slot 12
			b.close(wb);
			System.out.println("T5 quit with the chest open: put glass back, disconnect without closing");
			final int wb2 = b.open(2, 100, 0);
			b.click(wb2, 30); b.click(wb2, 7);
			b.disconnect();
			Thread.sleep(500);
			System.out.println("T6 dispenser: put 10 stone");
			w = a.open(0, 100, -3);
			a.click(w, 12); a.click(w, 0);
			a.close(w);
			System.out.println("T7 chest removed while open: nothing logged for the viewer");
			w = a.open(2, 100, 0);
			server.cmd("lbt air 2 100 0");
			server.waitFor("LBT air done", 10);
			a.waitClosedByServer(w);
			Thread.sleep(500);
			a.disconnect();
			Thread.sleep(500);
		} catch (final Exception ex) {
			ex.printStackTrace(System.out);
			failures++;
		}
		if (!server.stop())
			failures++;

		// player x y z itemtype itemamount, in log order
		final String[] expected = {
				"botA 2 100 0 1 -10", "botA 2 100 0 20 20", // T1
				"botA -2 100 0 4 -7", "botA -2 100 1 5 -9", // T2
				"botA 2 100 0 3 -5", "botB 2 100 0 20 -20", // T4
				"botB 2 100 0 20 20", // T5
				"botA 0 100 -3 1 10"}; // T6
		final List<String> actual = new ArrayList<String>();
		final Connection conn = DriverManager.getConnection(jdbc, user, password);
		try {
			final ResultSet rs = conn.createStatement().executeQuery("SELECT playername, x, y, z, itemtype, itemamount FROM `lb-world` INNER JOIN `lb-players` USING (playerid) INNER JOIN `lb-world-chest` USING (id) ORDER BY id");
			while (rs.next())
				actual.add(rs.getString(1) + " " + rs.getInt(2) + " " + rs.getInt(3) + " " + rs.getInt(4) + " " + rs.getInt(5) + " " + rs.getInt(6));
		} finally {
			conn.close();
		}
		System.out.println("chest rows:");
		for (final String row : actual)
			System.out.println("  " + row);
		if (!actual.equals(Arrays.asList(expected))) {
			System.out.println("MISMATCH, expected:");
			for (final String row : expected)
				System.out.println("  " + row);
			failures++;
		}
		server.printProblems();
		System.out.println(failures == 0 ? "ALL PASSED" : "FAILED (server log: " + server.logFile() + ")");
		System.exit(failures == 0 ? 0 : 1);
	}
}
