package dev.guildquests.update;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

final class UpdateInstallation {
    private UpdateInstallation() { }

    static Path prepare(Path currentJar, Path modsDir, Path updateDir, Path staged, String version, String loader) throws IOException {
        Path current = currentJar.toAbsolutePath().normalize();
        Path mods = modsDir.toAbsolutePath().normalize();
        Path dir = updateDir.toAbsolutePath().normalize();
        if (!mods.equals(current.getParent()) || !Files.isRegularFile(current) || Files.isSymbolicLink(current)) {
            throw new IOException("The running Mod must be a regular JAR directly inside mods");
        }
        if (!dir.equals(staged.toAbsolutePath().normalize().getParent())) throw new IOException("Invalid staging directory");
        String currentHash = UpdateInstaller.sha256(current);
        Properties p = new Properties();
        p.setProperty("loader", loader);
        p.setProperty("modsDir", mods.toString());
        p.setProperty("currentFile", current.getFileName().toString());
        p.setProperty("targetFile", ServerReleaseClient.jarName(loader, version));
        p.setProperty("stagedFile", staged.getFileName().toString());
        p.setProperty("sha256", UpdateInstaller.sha256(staged));
        p.setProperty("currentSha256", currentHash);
        p.setProperty("backupFile", current.getFileName() + "." + currentHash.substring(0, 12) + ".bak");
        p.setProperty("parentPid", Long.toString(ProcessHandle.current().pid()));
        p.setProperty("parentStartedAt", ProcessHandle.current().info().startInstant().orElseThrow().toString());

        // Extract the helper so it does not hold the old Mod JAR open on Windows.
        Files.createDirectories(dir);
        Path helper = dir.resolve("installer.jar");
        Path helperTemp = dir.resolve("installer.jar.tmp");
        String resource = "dev/guildquests/update/UpdateInstaller.class";
        try (var in = UpdateInstaller.class.getClassLoader().getResourceAsStream(resource);
             var out = new JarOutputStream(Files.newOutputStream(helperTemp))) {
            if (in == null) throw new IOException("Missing installation helper");
            out.putNextEntry(new JarEntry(resource));
            in.transferTo(out);
            out.closeEntry();
        }
        UpdateInstaller.atomicMove(helperTemp, helper);
        Path plan = dir.resolve("pending-install.properties");
        Path temporary = dir.resolve("pending-install.properties.tmp");
        try (var out = Files.newOutputStream(temporary)) { p.store(out, "GuildQuests verified update"); }
        UpdateInstaller.atomicMove(temporary, plan);
        return plan;
    }

    static void launchHelper(Path plan) throws IOException {
        Path dir = plan.toAbsolutePath().getParent();
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", executable);
        new ProcessBuilder(java.toString(), "-cp", dir.resolve("installer.jar").toString(),
                UpdateInstaller.class.getName(), plan.toAbsolutePath().toString())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(dir.resolve("installer.log").toFile())).start();
    }
}
