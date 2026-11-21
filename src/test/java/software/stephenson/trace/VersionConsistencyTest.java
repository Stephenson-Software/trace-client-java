package software.stephenson.trace;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every place the version is written agrees with pom.xml's project version,
 * so a release bump that misses one fails the build.
 */
class VersionConsistencyTest {

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    /** The single capture of {@code regex} in {@code text}; fails unless it matches exactly once. */
    private static String only(String text, String regex, String where) {
        Matcher m = Pattern.compile(regex).matcher(text);
        assertTrue(m.find(), where + ": no match for " + regex);
        String value = m.group(1);
        assertFalse(m.find(), where + ": more than one match for " + regex);
        return value;
    }

    private static String pomVersion() throws Exception {
        return only(read("pom.xml"),
                "<artifactId>trace-client</artifactId>\\s*<version>([^<]+)</version>", "pom.xml");
    }

    @Test
    void sourceHeaderMatchesPom() throws Exception {
        String source = read("src/main/java/software/stephenson/trace/TraceClient.java");
        assertEquals(pomVersion(), only(source, "trace-client (\\S+) --", "TraceClient.java header"));
    }

    @Test
    void versionConstantMatchesPom() throws Exception {
        String source = read("src/main/java/software/stephenson/trace/TraceClient.java");
        assertEquals(pomVersion(), only(source, "VERSION = \"([^\"]+)\"", "TraceClient.VERSION"));
        assertEquals(pomVersion(), TraceClient.VERSION);
    }

    @Test
    void readmeDependencySnippetMatchesPom() throws Exception {
        assertEquals(pomVersion(), only(read("README.md"), "<version>([^<]+)</version>", "README.md"));
    }
}
