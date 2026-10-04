package de.everforge.mod.server.audit;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.function.Function;
import java.util.regex.Pattern;

/** One writer-owned lock covers append, UTC daily rotation and retention. */
final class CommandAuditStore {
    private static final Pattern ARCHIVE = Pattern.compile("commands-\\d{4}-\\d{2}-\\d{2}(?:-\\d+)?\\.jsonl");
    private static final Duration RETENTION = Duration.ofDays(30);
    private final Path active;
    private final Function<String, Instant> timestamp;
    private Instant lastMaintenance;

    record Result(long removed, long invalid) { }

    CommandAuditStore(Path active, Function<String, Instant> timestamp) {
        this.active = active;
        this.timestamp = timestamp;
    }

    synchronized void append(String line, Instant now) throws IOException {
        if (lastMaintenance == null || !day(lastMaintenance).equals(day(now))
                || now.isBefore(lastMaintenance)
                || !now.isBefore(lastMaintenance.plus(Duration.ofHours(1)))) {
            try { maintain(now); }
            catch (IOException e) {
                // A failed archive cleanup must not discard a new audit event.
                System.err.println("[Everforge] Command audit cleanup before append failed: " + e);
            }
        }
        ensureDirectory();
        if (Files.exists(active, LinkOption.NOFOLLOW_LINKS)) requireFile(active);
        Files.writeString(active, line + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        Files.setLastModifiedTime(active, FileTime.from(now));
    }

    synchronized Result maintain(Instant now) throws IOException {
        ensureDirectory();
        if (Files.exists(active, LinkOption.NOFOLLOW_LINKS)) {
            requireFile(active);
            Instant modified = Files.getLastModifiedTime(active, LinkOption.NOFOLLOW_LINKS).toInstant();
            if (day(modified).isBefore(day(now))) {
                Path archive = active.resolveSibling("commands-" + day(modified) + ".jsonl");
                int suffix = 1;
                while (Files.exists(archive, LinkOption.NOFOLLOW_LINKS)) {
                    archive = active.resolveSibling("commands-" + day(modified) + "-" + suffix++ + ".jsonl");
                }
                Files.move(active, archive, StandardCopyOption.ATOMIC_MOVE);
            }
        }
        long removed = 0, invalid = 0;
        try (DirectoryStream<Path> paths = Files.newDirectoryStream(active.getParent())) {
            for (Path file : paths) {
                if (!file.equals(active) && !ARCHIVE.matcher(file.getFileName().toString()).matches()) continue;
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue;
                Result result = prune(file, now.minus(RETENTION));
                removed += result.removed(); invalid += result.invalid();
            }
        }
        lastMaintenance = now;
        return new Result(removed, invalid);
    }

    private Result prune(Path file, Instant cutoff) throws IOException {
        Path temp = Files.createTempFile(active.getParent(), ".command-audit-retention-", ".tmp");
        long removed = 0, invalid = 0, kept = 0;
        try {
            try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8);
                 BufferedWriter writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;
                    Instant instant;
                    try { instant = timestamp.apply(line); } catch (RuntimeException e) { instant = null; }
                    if (instant != null && !instant.isAfter(cutoff)) { removed++; continue; }
                    if (instant == null) invalid++;
                    writer.write(line); writer.newLine(); kept++;
                }
            }
            if (kept == 0 && !file.equals(active)) Files.delete(file);
            else if (removed > 0) Files.move(temp, file,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return new Result(removed, invalid);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private void ensureDirectory() throws IOException {
        Files.createDirectories(active.getParent());
        if (Files.isSymbolicLink(active.getParent())) throw new IOException("Audit directory is a symlink");
    }

    private static void requireFile(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Audit path is not a regular file: " + file);
    }

    private static LocalDate day(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC).toLocalDate();
    }
}
