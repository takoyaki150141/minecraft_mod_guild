package dev.guildquests.update;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.stream.Collectors;

/**
 * Standalone helper run in a separate JVM (spawned on game exit) to finish an update.
 * <p>
 * The running mod JAR is locked by the loader while the game is open, so the swap cannot happen
 * in-process. This helper waits until the lock is released (the old JAR becomes deletable),
 * removes every guildquests JAR from the mods folder, then moves the staged JAR into place.
 * It only touches {@code java.nio} APIs, so it runs in a plain JVM with no Minecraft classes.
 * <p>
 * Its stdout/stderr are discarded by the launcher, so progress is appended to
 * {@code <stagedDir>/swap.log} for diagnosis.
 * <p>
 * Args: {@code <modsDir> <stagedJar> <targetJar> <sha256>}.
 */
public final class UpdateApplier {
    private UpdateApplier() {
    }

    public static void main(String[] args) {
        if (args.length < 4) {
            return;
        }
        Path modsDir = Path.of(args[0]);
        Path staged = Path.of(args[1]);
        Path target = Path.of(args[2]);
        String expectedSha256 = args[3];
        Path logFile = staged.getParent() != null ? staged.getParent().resolve("swap.log") : null;

        log(logFile, "=== swap start ===");
        log(logFile, "modsDir=" + modsDir);
        log(logFile, "staged=" + staged);
        log(logFile, "target=" + target);
        log(logFile, "sha256=" + expectedSha256);

        if (!expectedSha256.matches("[0-9a-f]{64}")) {
            log(logFile, "missing or invalid SHA-256; aborting");
            return;
        }

        try {
            if (!Files.exists(staged)) {
                log(logFile, "staged JAR missing; nothing to do");
                return;
            }
            if (!matches(staged, expectedSha256)) {
                log(logFile, "staged JAR SHA-256 mismatch; aborting");
                return;
            }

            // Wait for the game to release its lock on the running JAR (up to ~5 minutes).
            int attempts = 0;
            for (int i = 0; i < 300; i++) {
                attempts++;
                boolean allRemoved = true;
                StringBuilder removed = new StringBuilder();
                StringBuilder failed = new StringBuilder();
                try (var stream = Files.list(modsDir)) {
                    for (Path p : (Iterable<Path>) stream::iterator) {
                        String name = p.getFileName().toString();
                        if (name.startsWith("guildquests_") && name.endsWith(".jar")) {
                            try {
                                Files.delete(p);
                                removed.append(name).append(' ');
                            } catch (Exception e) {
                                allRemoved = false;
                                failed.append(name).append("(").append(e.getClass().getSimpleName()).append(") ");
                            }
                        }
                    }
                } catch (Exception e) {
                    allRemoved = false;
                    log(logFile, "list failed: " + e);
                }

                if (allRemoved) {
                    log(logFile, "attempt " + attempts + ": cleared old JARs [" + removed.toString().trim() + "]");
                    break;
                }
                if (i % 5 == 0) {
                    log(logFile, "attempt " + attempts + ": waiting, still locked [" + failed.toString().trim() + "]");
                }
                Thread.sleep(1000);
            }

            // The file must still match right before it is published.
            if (!matches(staged, expectedSha256)) {
                log(logFile, "staged JAR changed during the wait; aborting");
                return;
            }
            Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(staged.resolveSibling(staged.getFileName() + ".sha256"));
            log(logFile, "moved staged -> target (SHA-256 verified)");
        } catch (Exception e) {
            log(logFile, "ERROR: " + e);
        } finally {
            log(logFile, "mods now: " + listing(modsDir));
            log(logFile, "=== swap end ===");
        }
        System.exit(0);
    }

    private static boolean matches(Path file, String expectedSha256) {
        try {
            return expectedSha256.equalsIgnoreCase(UpdateInstaller.sha256(file));
        } catch (Exception e) {
            return false;
        }
    }

    private static String listing(Path dir) {
        try (var stream = Files.list(dir)) {
            return stream.map(p -> p.getFileName().toString()).collect(Collectors.joining(", "));
        } catch (Exception e) {
            return "<list failed: " + e + ">";
        }
    }

    private static void log(Path file, String message) {
        if (file == null) {
            return;
        }
        try {
            Files.writeString(file, "[" + Instant.now() + "] " + message + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }
}
