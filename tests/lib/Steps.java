import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Steps of an end-to-end test: a failed step is counted and printed, and the test goes on with the next one */
public class Steps
{
	static int failures = 0;

	interface Step
	{
		void run() throws Exception;
	}

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

	static String read(File file) throws Exception {
		return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
	}

	static void write(File file, String text) throws Exception {
		Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
	}

	/** Prints the result and exits with 0 if nothing failed */
	static void finish(TestServer server) {
		System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED (server log: " + server.logFile() + ")");
		System.exit(failures == 0 ? 0 : 1);
	}
}
