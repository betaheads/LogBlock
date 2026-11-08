import java.io.*;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.*;

/**
 * Minimal b1.7.3 client (protocol version 14) for the end-to-end tests, see tests/README.md: logs in to the TestServer,
 * stays in sync, chats and types commands, opens containers and clicks slots.
 */
public class Bot
{
	final String name;
	Socket socket;
	DataInputStream in;
	DataOutputStream out;
	volatile boolean posReady = false, closed = false;
	final BlockingQueue<Integer> opened = new LinkedBlockingQueue<Integer>();
	final BlockingQueue<Integer> closedByServer = new LinkedBlockingQueue<Integer>();
	/** Chat lines from the server, without color codes */
	final BlockingQueue<String> chat = new LinkedBlockingQueue<String>();
	short action = 1;

	Bot(String name) {
		this.name = name;
	}

	void connect() throws Exception {
		socket = new Socket("127.0.0.1", TestServer.PORT);
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

	/**
	 * Right click on the top face of a block. The packet says empty hand, but the server uses the item the player
	 * really holds: hotbar slot 0, the bot never changes it.
	 */
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

	/** Sends a chat message or a command like "/lb lookup". Drops the chat lines received before. */
	synchronized void say(String message) throws IOException {
		chat.clear();
		out.writeByte(0x03);
		str16(message);
		out.flush();
	}

	/** Like say, then waits for a chat line containing reply and returns it */
	String say(String message, String reply) throws Exception {
		say(message);
		return expect(reply);
	}

	/** Waits up to 5 seconds for a chat line containing text, drops the lines before it */
	String expect(String text) throws Exception {
		final List<String> lines = collect(text);
		return lines.get(lines.size() - 1);
	}

	/** Chat lines up to and including the first one containing last, waits up to 5 seconds for it */
	List<String> collect(String last) throws Exception {
		final List<String> lines = new ArrayList<String>();
		final long end = System.currentTimeMillis() + 5000;
		while (true) {
			final String line = chat.poll(Math.max(1, end - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
			if (line == null)
				throw new Exception(name + ": no chat line containing '" + last + "', got " + lines);
			lines.add(line);
			if (line.contains(last))
				return lines;
		}
	}

	/** Fails if any chat line comes within millis */
	void expectNothing(int millis) throws Exception {
		final String line = chat.poll(millis, TimeUnit.MILLISECONDS);
		if (line != null)
			throw new Exception(name + ": expected no answer, got '" + line + "'");
	}

	/**
	 * /lb permissions: exactly these permissions (without "logblock.", in the order of PermissionGroups.PERMISSIONS)
	 * and "Your groups: groups"
	 */
	void expectPermissions(String groups, String... permissions) throws Exception {
		say("/lb permissions");
		final List<String> lines = collect("Your groups:");
		final List<String> actual = new ArrayList<String>();
		for (final String line : lines)
			if (line.startsWith("logblock."))
				actual.add(line.substring("logblock.".length()));
		if (!actual.equals(Arrays.asList(permissions)))
			throw new Exception(name + ": permissions " + actual + ", expected " + Arrays.asList(permissions));
		if (permissions.length == 0 && !lines.contains("none"))
			throw new Exception(name + ": no 'none' in " + lines);
		final String last = lines.get(lines.size() - 1);
		if (!last.equals("Your groups: " + groups))
			throw new Exception(name + ": " + last + ", expected groups " + groups);
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
			case 0x03: chat.add(readStr16().replaceAll("§.", "")); break; // without color codes
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
