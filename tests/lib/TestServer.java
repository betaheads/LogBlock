import java.io.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * The Poseidon server of an end-to-end test (tests/[test]/run.sh, see tests/README.md): starts it in a directory prepared by
 * tests/lib/common.sh, types into its console and waits for its output. System property test.cp: the server's classpath.
 */
public class TestServer
{
	static final int PORT = 25599;
	final File dir;
	final Process process;
	private final BufferedWriter console;
	private final List<String> output = Collections.synchronizedList(new ArrayList<String>());

	/** Starts the server and waits until it is up */
	TestServer(File dir) throws Exception {
		this.dir = dir;
		final ProcessBuilder pb = new ProcessBuilder("java", "-Xmx512M", "-cp", System.getProperty("test.cp"), "org.bukkit.craftbukkit.Main", "--nojline", "nogui");
		pb.directory(dir);
		pb.redirectErrorStream(true);
		process = pb.start();
		console = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
		final PrintWriter logFile = new PrintWriter(new FileWriter(logFile()), true);
		new Thread() {
			@Override
			public void run() {
				try {
					final BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream()));
					String line;
					while ((line = r.readLine()) != null) {
						output.add(line);
						logFile.println(line);
					}
				} catch (final IOException ex) {}
			}
		}.start();
		try {
			waitFor("Done (", 180);
		} catch (final Exception ex) {
			process.destroyForcibly();
			throw new Exception(ex.getMessage() + ", see " + logFile());
		}
		System.out.println("server up");
	}

	File logFile() {
		return new File(dir, "test-server-output.log");
	}

	/** Types a command into the server console */
	void cmd(String command) throws IOException {
		console.write(command + "\n");
		console.flush();
	}

	/** Number of output lines so far, for waitFor(from, ...) */
	int mark() {
		return output.size();
	}

	/** Waits for an output line containing text, anywhere since the start */
	String waitFor(String text, int seconds) throws Exception {
		return waitFor(0, text, seconds);
	}

	/** Waits for an output line containing text, from line number from on (see mark()) */
	String waitFor(int from, String text, int seconds) throws Exception {
		final long end = System.currentTimeMillis() + seconds * 1000L;
		while (System.currentTimeMillis() < end) {
			synchronized (output) {
				for (int i = from; i < output.size(); i++)
					if (output.get(i).contains(text))
						return output.get(i);
			}
			Thread.sleep(100);
		}
		throw new Exception("timeout waiting for server output: " + text);
	}

	/** Output lines so far that contain all the texts */
	List<String> linesWith(String... texts) {
		final List<String> result = new ArrayList<String>();
		synchronized (output) {
			for (final String line : output) {
				boolean all = true;
				for (final String text : texts)
					all &= line.contains(text);
				if (all)
					result.add(line);
			}
		}
		return result;
	}

	/** @return false if it had to be killed */
	boolean stop() throws Exception {
		cmd("stop");
		if (process.waitFor(60, TimeUnit.SECONDS)) {
			System.out.println("server stopped");
			return true;
		}
		process.destroyForcibly();
		System.out.println("server did not stop, killed");
		return false;
	}

	/** Prints exceptions, errors and LogBlock warnings from the server output, except the expected ones */
	void printProblems(String... expected) {
		synchronized (output) {
			for (final String line : output)
				if ((line.contains("Exception") || line.contains("SEVERE") || line.contains("[LogBlock]") && line.contains("WARN")) && !line.contains("SSL connection") && !containsAny(line, expected))
					System.out.println("server: " + line);
		}
	}

	private static boolean containsAny(String line, String[] texts) {
		for (final String text : texts)
			if (line.contains(text))
				return true;
		return false;
	}

	/** Drops the LogBlock tables of world "world", so a test starts with an empty log. Creates the database if needed. */
	static void dropTables(String jdbc, String user, String password) throws SQLException {
		final Connection conn = DriverManager.getConnection(jdbc + (jdbc.contains("?") ? "&" : "?") + "createDatabaseIfNotExist=true", user, password);
		try {
			conn.createStatement().execute("DROP TABLE IF EXISTS `lb-players`, `lb-world`, `lb-world-sign`, `lb-world-chest`");
		} finally {
			conn.close();
		}
	}
}
