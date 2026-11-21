package software.stephenson.trace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Freezes what {@link TraceClient#installIdFromFile(File)} does with the
 * install-ID files earlier releases may have left on users' disks. Each
 * fixture in {@code src/test/resources/install-id-fixtures} is listed in that
 * directory's {@code expected.txt} as {@code loaded <id>}, {@code replaced}
 * or {@code kept}. Released fixtures and their loaded/kept lines must never
 * change; a new case is a new fixture plus its line.
 */
class InstallIdFixturesTest {

    private static final String FIXTURE_DIR = "install-id-fixtures";
    private static final String EXPECTED = "expected.txt";
    private static final Pattern UUID4 =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");

    @TempDir
    Path temp;

    private static Path fixtureDir() throws Exception {
        URL url = InstallIdFixturesTest.class.getClassLoader().getResource(FIXTURE_DIR);
        assertNotNull(url, FIXTURE_DIR + " is not on the test classpath");
        return Paths.get(url.toURI());
    }

    private static List<String[]> expectedLines(Path dir) throws Exception {
        List<String[]> lines = new ArrayList<>();
        for (String line : Files.readAllLines(dir.resolve(EXPECTED), StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            lines.add(trimmed.split(" "));
        }
        return lines;
    }

    @Test
    void expectedTxtNamesEveryFixtureAndNothingElse() throws Exception {
        Path dir = fixtureDir();
        TreeSet<String> onDisk = new TreeSet<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.id")) {
            for (Path file : files) {
                onDisk.add(file.getFileName().toString());
            }
        }
        TreeSet<String> listed = new TreeSet<>();
        for (String[] line : expectedLines(dir)) {
            assertTrue(listed.add(line[0]), EXPECTED + " lists " + line[0] + " twice");
        }
        assertEquals(onDisk, listed, "the *.id files in " + FIXTURE_DIR + " and the files " + EXPECTED + " names");
    }

    @Test
    void everyFixtureLoadsAsReleased() throws Exception {
        Path dir = fixtureDir();
        List<String[]> lines = expectedLines(dir);
        assertFalse(lines.isEmpty(), EXPECTED + " lists no fixtures");
        int n = 0;
        for (String[] line : lines) {
            String name = line[0];
            String outcome = line.length > 1 ? line[1] : "";
            byte[] original = Files.readAllBytes(dir.resolve(name));
            Path work = Files.createDirectory(temp.resolve("case-" + n++));
            Path copy = work.resolve(name);
            Files.write(copy, original);

            String id = TraceClient.installIdFromFile(copy.toFile());
            byte[] after = Files.readAllBytes(copy);

            if (outcome.equals("loaded")) {
                assertEquals(3, line.length, "malformed line for " + name);
                assertEquals(line[2], id, name + ": loaded ID");
                assertArrayEquals(original, after, name + ": file must be left untouched");
            } else if (outcome.equals("replaced")) {
                assertEquals(2, line.length, "malformed line for " + name);
                assertTrue(UUID4.matcher(id).matches(), name + ": not a lowercase uuid4: " + id);
                assertArrayEquals((id + "\n").getBytes(StandardCharsets.UTF_8), after,
                        name + ": file must now hold exactly the new ID and a newline");
            } else if (outcome.equals("kept")) {
                assertEquals(2, line.length, "malformed line for " + name);
                assertArrayEquals(original, after, name + ": unreadable file must be left byte-identical");
                assertTrue(UUID4.matcher(id).matches(), name + ": not a lowercase uuid4: " + id);
                assertFalse(new String(original, StandardCharsets.ISO_8859_1).contains(id),
                        name + ": returned ID must be fresh, not one from the file");
            } else {
                fail("unknown outcome in " + EXPECTED + ": " + Arrays.toString(line));
            }
        }
    }
}
