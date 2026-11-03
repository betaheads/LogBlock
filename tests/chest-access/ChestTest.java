import java.io.*;
import java.net.Socket;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * End-to-end test of chest access logging (LBChestAccessListener). Started by run.sh, see tests/README.md.
 *
 * Starts the Poseidon server in serverDir, connects two bots that speak the b1.7.3 protocol (version 14),
 * builds the scene with the LBTest helper plugin (/lbt setup), opens containers, moves items, stops the server
 * and compares the rows in the -chest table with the expected ones.
 *
 * Args: serverDir jdbcUrl user password. System property test.cp: classpath of the server.
 * The LogBlock tables of world "world" in the database are dropped before the test.
 */
public class ChestTest
{
	static final int PORT = 25599;
	static Process server;
	static BufferedWriter console;
	static final List<String> serverOut = Collections.synchronizedList(new ArrayList<String>());

	public static void main(String[] args) throws Exception {
		final File dir = new File(args[0]);
		final String jdbc = args[1], user = args[2], password = args[3];
		dropTables(jdbc, user, password);

		final ProcessBuilder pb = new ProcessBuilder("java", "-Xmx512M", "-cp", System.getProperty("test.cp"), "org.bukkit.craftbukkit.Main", "--nojline", "nogui");
		pb.directory(dir);
		pb.redirectErrorStream(true);
		server = pb.start();
		console = new BufferedWriter(new OutputStreamWriter(server.getOutputStream()));
		final PrintWriter logFile = new PrintWriter(new FileWriter(new File(dir, "test-server-output.log")), true);
		new Thread() {
			@Override
			public void run() {
				try {
					final BufferedReader r = new BufferedReader(new InputStreamReader(server.getInputStream()));
					String line;
					while ((line = r.readLine()) != null) {
						serverOut.add(line);
						logFile.println(line);
					}
				} catch (final IOException ex) {}
			}
		}.start();
		int failures = 0;
		try {
			waitFor("Done (", 180);
			System.out.println("server up");
			final Bot a = new Bot("botA"), b = new Bot("botB");
			a.connect();
			b.connect();
			a.waitPos();
			b.waitPos();
			cmd("lbt setup botA botB");
			waitFor("LBT setup done", 10);
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
			cmd("lbt air 2 100 0");
			waitFor("LBT air done", 10);
			a.waitClosedByServer(w);
			Thread.sleep(500);
			a.disconnect();
			Thread.sleep(500);
		} catch (final Exception ex) {
			ex.printStackTrace(System.out);
			failures++;
		}
		cmd("stop");
		if (!server.waitFor(60, TimeUnit.SECONDS)) {
			server.destroyForcibly();
			System.out.println("server did not stop, killed");
			failures++;
		} else
			System.out.println("server stopped");

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
		for (final String line : serverOut)
			if ((line.contains("Exception") || line.contains("SEVERE") || line.contains("[LogBlock]") && line.contains("WARN")) && !line.contains("SSL connection"))
				System.out.println("server: " + line);
		System.out.println(failures == 0 ? "ALL PASSED" : "FAILED (server log: " + new File(dir, "test-server-output.log") + ")");
		System.exit(failures == 0 ? 0 : 1);
	}

	static void dropTables(String jdbc, String user, String password) throws SQLException {
		final Connection conn = DriverManager.getConnection(jdbc + (jdbc.contains("?") ? "&" : "?") + "createDatabaseIfNotExist=true", user, password);
		try {
			conn.createStatement().execute("DROP TABLE IF EXISTS `lb-players`, `lb-world`, `lb-world-sign`, `lb-world-chest`");
		} finally {
			conn.close();
		}
	}

	static void cmd(String c) throws IOException {
		console.write(c + "\n");
		console.flush();
	}

	static void waitFor(String text, int seconds) throws Exception {
		final long end = System.currentTimeMillis() + seconds * 1000L;
		while (System.currentTimeMillis() < end) {
			synchronized (serverOut) {
				for (final String l : serverOut)
					if (l.contains(text))
						return;
			}
			Thread.sleep(100);
		}
		throw new Exception("timeout waiting for server output: " + text);
	}

	/** Minimal b1.7.3 client: logs in, stays in sync, opens containers and clicks slots. */
	static class Bot
	{
		final String name;
		Socket socket;
		DataInputStream in;
		DataOutputStream out;
		volatile boolean posReady = false, closed = false;
		final BlockingQueue<Integer> opened = new LinkedBlockingQueue<Integer>();
		final BlockingQueue<Integer> closedByServer = new LinkedBlockingQueue<Integer>();
		short action = 1;

		Bot(String name) {
			this.name = name;
		}

		void connect() throws Exception {
			socket = new Socket("127.0.0.1", PORT);
			in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
			out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
			synchronized (this) {
				out.writeByte(0x02);
				str16(name);
				out.flush();
			}
			if (in.readUnsignedByte() != 0x02)
				throw new Exception(name + ": no handshake");
			readStr16();
			synchronized (this) {
				out.writeByte(0x01);
				out.writeInt(14);
				str16(name);
				out.writeLong(0);
				out.writeByte(0);
				out.flush();
			}
			new Thread() {
				@Override
				public void run() {
					try {
						while (!closed)
							readPacket();
					} catch (final Exception ex) {
						if (!closed)
							System.out.println(name + " reader: " + ex);
					}
				}
			}.start();
			new Thread() {
				@Override
				public void run() {
					try {
						while (!closed) {
							Thread.sleep(50);
							if (posReady)
								synchronized (Bot.this) {
									out.writeByte(0x0A);
									out.writeBoolean(true);
									out.flush();
								}
						}
					} catch (final Exception ex) {}
				}
			}.start();
		}

		void waitPos() throws Exception {
			for (int i = 0; i < 300 && !posReady; i++)
				Thread.sleep(100);
			if (!posReady)
				throw new Exception(name + ": never got a position");
		}

		int open(int x, int y, int z) throws Exception {
			place(x, y, z);
			final Integer id = opened.poll(5, TimeUnit.SECONDS);
			if (id == null)
				throw new Exception(name + ": container at " + x + "," + y + "," + z + " didn't open");
			Thread.sleep(300);
			return id;
		}

		void tryOpenExpectFail(int x, int y, int z) throws Exception {
			place(x, y, z);
			if (opened.poll(1500, TimeUnit.MILLISECONDS) != null)
				throw new Exception(name + ": blocked chest opened");
		}

		void waitClosedByServer(int w) throws Exception {
			final Integer id = closedByServer.poll(5, TimeUnit.SECONDS);
			if (id == null || id != w)
				throw new Exception(name + ": server didn't close window " + w);
		}

		/** Right click on the top face of a block with an empty hand */
		synchronized void place(int x, int y, int z) throws IOException {
			out.writeByte(0x0F);
			out.writeInt(x);
			out.writeByte(y);
			out.writeInt(z);
			out.writeByte(1);
			out.writeShort(-1);
			out.flush();
		}

		/**
		 * Left click on a window slot. The item in the packet is left empty, so the server rejects the transaction,
		 * but applies the click anyway; the 0x6A handler confirms the rejection like a real client does.
		 */
		void click(int window, int slot) throws Exception {
			synchronized (this) {
				out.writeByte(0x66);
				out.writeByte(window);
				out.writeShort(slot);
				out.writeByte(0);
				out.writeShort(action++);
				out.writeBoolean(false);
				out.writeShort(-1);
				out.flush();
			}
			Thread.sleep(250);
		}

		void close(int window) throws Exception {
			synchronized (this) {
				out.writeByte(0x65);
				out.writeByte(window);
				out.flush();
			}
			Thread.sleep(400);
		}

		void disconnect() throws IOException {
			closed = true;
			socket.close();
		}

		void str16(String s) throws IOException {
			out.writeShort(s.length());
			out.writeChars(s);
		}

		String readStr16() throws IOException {
			final int len = in.readShort();
			final StringBuilder sb = new StringBuilder();
			for (int i = 0; i < len; i++)
				sb.append(in.readChar());
			return sb.toString();
		}

		void skip(int n) throws IOException {
			in.readFully(new byte[n]);
		}

		void item() throws IOException {
			if (in.readShort() != -1)
				skip(3);
		}

		void metadata() throws IOException {
			int b;
			while ((b = in.readUnsignedByte()) != 0x7F)
				switch (b >> 5) {
					case 0: skip(1); break;
					case 1: skip(2); break;
					case 2: case 3: skip(4); break;
					case 4: readStr16(); break;
					case 5: skip(5); break;
					case 6: skip(12); break;
				}
		}

		/** The protocol has no packet lengths, so every packet the server may send has to be parsed. */
		void readPacket() throws Exception {
			final int id = in.readUnsignedByte();
			switch (id) {
				case 0x00:
					synchronized (this) {
						out.writeByte(0);
						out.flush();
					}
					break;
				case 0x01: skip(4); readStr16(); skip(9); break;
				case 0x03: readStr16(); break;
				case 0x04: skip(8); break;
				case 0x05: skip(10); break;
				case 0x06: skip(12); break;
				case 0x08: skip(2); break;
				case 0x09: skip(1); break;
				case 0x0D: {
					// Server sends x, stance, y, z; the client answers with x, y, stance, z to confirm the position
					final double x = in.readDouble(), stance = in.readDouble(), y = in.readDouble(), z = in.readDouble();
					final float yaw = in.readFloat(), pitch = in.readFloat();
					in.readBoolean();
					synchronized (this) {
						out.writeByte(0x0D);
						out.writeDouble(x);
						out.writeDouble(y);
						out.writeDouble(stance);
						out.writeDouble(z);
						out.writeFloat(yaw);
						out.writeFloat(pitch);
						out.writeBoolean(true);
						out.flush();
					}
					posReady = true;
					break;
				}
				case 0x11: skip(14); break;
				case 0x12: skip(5); break;
				case 0x14: skip(4); readStr16(); skip(16); break;
				case 0x15: skip(24); break;
				case 0x16: skip(8); break;
				case 0x17: {
					skip(17);
					if (in.readInt() > 0)
						skip(6);
					break;
				}
				case 0x18: skip(19); metadata(); break;
				case 0x19: skip(4); readStr16(); skip(16); break;
				case 0x1C: skip(10); break;
				case 0x1D: case 0x1E: skip(4); break;
				case 0x1F: skip(7); break;
				case 0x20: skip(6); break;
				case 0x21: skip(9); break;
				case 0x22: skip(18); break;
				case 0x26: skip(5); break;
				case 0x27: skip(8); break;
				case 0x28: skip(4); metadata(); break;
				case 0x32: skip(9); break;
				case 0x33: skip(13); skip(in.readInt()); break;
				case 0x34: skip(8); skip(in.readShort() * 4); break;
				case 0x35: skip(11); break;
				case 0x36: skip(12); break;
				case 0x3C: skip(28); skip(in.readInt() * 3); break;
				case 0x3D: skip(17); break;
				case 0x46: skip(1); break;
				case 0x47: skip(17); break;
				case 0x64: {
					final int window = in.readUnsignedByte();
					in.readByte();
					in.readUTF();
					in.readByte();
					opened.add(window);
					break;
				}
				case 0x65: closedByServer.add(in.readUnsignedByte()); break;
				case 0x67: skip(3); item(); break;
				case 0x68: {
					skip(1);
					final int n = in.readShort();
					for (int i = 0; i < n; i++)
						item();
					break;
				}
				case 0x69: skip(5); break;
				case 0x6A: {
					// A rejected click puts the player out of sync, further clicks are ignored until the client confirms
					final int window = in.readUnsignedByte();
					final short act = in.readShort();
					if (!in.readBoolean())
						synchronized (this) {
							out.writeByte(0x6A);
							out.writeByte(window);
							out.writeShort(act);
							out.writeBoolean(true);
							out.flush();
						}
					break;
				}
				case 0x82: skip(10); for (int i = 0; i < 4; i++) readStr16(); break;
				case 0x83: skip(4); skip(in.readUnsignedByte()); break;
				case 0xC8: skip(5); break;
				case 0xFF: throw new Exception("kicked: " + readStr16());
				default: throw new Exception("unknown packet 0x" + Integer.toHexString(id));
			}
		}
	}
}
