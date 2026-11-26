package dev.guildquests.update;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Properties;

/** Standalone Java 17 helper. Deliberately has no Mod, Minecraft, or third-party dependencies. */
public final class UpdateInstaller {
    private UpdateInstaller() { }

    public static void main(String[] args) {
        try {
            if (args.length != 1) throw new IOException("Usage: UpdateInstaller <pending-install.properties>");
            Path plan = Path.of(args[0]).toAbsolutePath().normalize();
            if (!Files.exists(plan)) return;
            Properties properties = readPlan(plan);
            long pid = Long.parseLong(properties.getProperty("parentPid"));
            Instant started = Instant.parse(properties.getProperty("parentStartedAt"));
            ProcessHandle parent = ProcessHandle.of(pid).orElse(null);
            if (parent != null && parent.info().startInstant().map(started::equals).orElse(false)) {
                while (parent.isAlive() && Files.exists(plan)) Thread.sleep(250);
            }
            install(plan);
        } catch (Exception e) {
            System.err.println("[GuildQuests Update] Installation failed. Retaining the plan for retry.");
            e.printStackTrace();
            System.exit(1);
        }
    }

    static void install(Path plan) throws IOException, InterruptedException {
        Path dir = plan.toAbsolutePath().normalize().getParent();
        Files.createDirectories(dir);
        // The helper and a restart launcher may both arrive here. Only one may change mods.
        try (FileChannel channel = FileChannel.open(dir.resolve("install.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE); var lock = channel.lock()) {
            if (!Files.exists(plan)) return;
            Properties p = readPlan(plan);
            Path mods = Path.of(p.getProperty("modsDir")).toAbsolutePath().normalize();
            Path current = directChild(mods, p.getProperty("currentFile"));
            Path target = directChild(mods, p.getProperty("targetFile"));
            Path staged = directChild(dir, p.getProperty("stagedFile"));
            Path backups = dir.resolve("backups");
            Path backup = directChild(backups, p.getProperty("backupFile"));
            String expected = p.getProperty("sha256");
            String oldHash = p.getProperty("currentSha256");
            String loader = p.getProperty("loader", "fabric");
            if ((!"fabric".equals(loader) && !"forge".equals(loader) && !"neoforge".equals(loader)) || current.equals(target) || !target.getFileName().toString().matches("guildquests_" + loader + "-1\\.21\\.1_[0-9]+(?:\\.[0-9]+)*\\.jar")
                    || !expected.matches("[0-9a-f]{64}") || !oldHash.matches("[0-9a-f]{64}")) {
                throw new IOException("Invalid installation plan");
            }
            if (Files.isSymbolicLink(mods) || Files.isSymbolicLink(dir) || Files.isSymbolicLink(backups)) {
                throw new IOException("Refusing symbolic-link installation directories");
            }
            if (Files.exists(target) && !matches(target, expected)) throw new IOException("Target already exists with different contents");
            if (Files.exists(current) && !matches(current, oldHash)) throw new IOException("Current Mod was changed; refusing replacement");
            if (!Files.exists(target) && !matches(staged, expected)) throw new IOException("Staged JAR was changed or removed");
            Files.createDirectories(backups);
            if (!matches(backup, oldHash)) {
                if (!matches(current, oldHash)) throw new IOException("Neither original Mod nor backup is intact");
                Files.copy(current, backup, StandardCopyOption.REPLACE_EXISTING);
                if (!matches(backup, oldHash)) throw new IOException("Backup verification failed");
            }

            IOException failure = null;
            for (int attempt = 0; attempt < 60; attempt++) {
                try {
                    if (!Files.exists(target)) {
                        Path temporary = target.resolveSibling(target.getFileName() + ".installing");
                        Files.copy(staged, temporary, StandardCopyOption.REPLACE_EXISTING);
                        if (!matches(temporary, expected)) throw new IOException("Installation copy verification failed");
                        Files.deleteIfExists(current);
                        atomicMove(temporary, target);
                    } else {
                        Files.deleteIfExists(current);
                    }
                    if (!matches(target, expected)) throw new IOException("Installed JAR verification failed");
                    Files.deleteIfExists(staged);
                    Files.deleteIfExists(dir.resolve("detected-update.json"));
                    Files.delete(plan);
                    System.out.println("[GuildQuests Update] Installed " + target.getFileName() + "; backup: " + backup);
                    return;
                } catch (IOException e) {
                    failure = e;
                    // Restore the original if publishing the replacement failed after removing it.
                    if (!Files.exists(current) && !Files.exists(target)) {
                        try { Files.copy(backup, current); } catch (IOException restore) { e.addSuppressed(restore); }
                    }
                    Thread.sleep(500);
                }
            }
            throw failure;
        }
    }

    private static Path directChild(Path directory, String name) throws IOException {
        if (name == null || name.isBlank() || name.contains("/") || name.contains("\\") || name.equals(".") || name.equals("..")) {
            throw new IOException("Invalid filename in installation plan");
        }
        Path child = directory.resolve(name).toAbsolutePath().normalize();
        if (!directory.toAbsolutePath().normalize().equals(child.getParent()) || Files.isSymbolicLink(child)) {
            throw new IOException("Installation path escapes its directory");
        }
        return child;
    }

    private static boolean matches(Path path, String hash) throws IOException {
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && hash.equals(sha256(path));
    }

    static Properties readPlan(Path plan) throws IOException {
        Properties properties = new Properties();
        try (var in = Files.newInputStream(plan)) { properties.load(in); }
        return properties;
    }

    public static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = in.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static void atomicMove(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
