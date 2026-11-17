package dev.guildquests.update;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.guildquests.GuildQuestsMod;
import dev.guildquests.config.ServerUpdateConfig;
import dev.guildquests.platform.Platform;
import net.minecraft.server.MinecraftServer;
import net.minecraft.network.chat.Component;
import net.minecraft.commands.Commands;
import dev.guildquests.config.ConfigManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** All state transitions run on the server thread; only HTTP/disk preparation runs on the worker. */
public final class ServerUpdateService {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static ServerUpdateService active;
    private final MinecraftServer server;
    private final Path configFile = Platform.configDir().resolve("server-update.json");
    private final Path dir = Platform.configDir().resolve("update");
    private final Path detectionFile = dir.resolve("detected-update.json");
    private final Path plan = dir.resolve("pending-install.properties");
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "GuildQuests-ServerUpdate");
        thread.setDaemon(true);
        return thread;
    });
    private ServerUpdateConfig config = disabledConfig();
    private UpdateSchedule schedule = new UpdateSchedule(null, 0);
    private Future<?> operation;
    private boolean busy;
    private boolean staged;
    private boolean helperStarted;
    private boolean closed;
    private boolean blockedByPlan;
    private int generation;
    private long nextCheckAt;
    private long retryAt;
    private long lastRemaining = Long.MAX_VALUE;

    private record Detection(ServerReleaseClient.UpdateInfo update, long detectedAt) { }

    private ServerUpdateService(MinecraftServer server) {
        this.server = server;
        GuildQuestsMod.LOGGER.info("[Server Update] Started: loader={}, current={}", Platform.getLoaderName(), currentVersion());
        reload();
        try {
            if (Files.exists(detectionFile)) {
                Detection saved = GSON.fromJson(Files.readString(detectionFile), Detection.class);
                if (saved != null && saved.update() != null && saved.detectedAt() > 0
                        && ServerReleaseClient.compareVersions(saved.update().latestVersion(), currentVersion()) > 0) {
                    ServerReleaseClient.requireHttpUri(saved.update().downloadUrl());
                    schedule = new UpdateSchedule(saved.update(), Math.min(saved.detectedAt(), System.currentTimeMillis()));
                }
            }
        } catch (Exception e) {
            GuildQuestsMod.LOGGER.warn("[Server Update] Could not restore the detection time", e);
        }
        blockedByPlan = Files.exists(plan);
        if (blockedByPlan) GuildQuestsMod.LOGGER.error("[Server Update] A previous installation is pending. Run start-server.ps1 to recover it: {}", plan);
    }

    private static ServerUpdateConfig disabledConfig() {
        ServerUpdateConfig disabled = new ServerUpdateConfig().sanitize();
        disabled.enabled = false;
        return disabled;
    }

    private static String currentVersion() {
        return Platform.getModVersion();
    }

    private static Path runningModJar() {
        return Platform.getModJar();
    }

    public static void register() {
        ConfigManager.onReload(ServerUpdateService::reloadActive);
        Platform.onCommands((dispatcher, registry) -> dispatcher.register(Commands.literal("guildadmin")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("update")
                        .executes(context -> {
                            context.getSource().sendSuccess(() -> Component.literal(status()), false);
                            return 1;
                        })
                        .then(Commands.literal("status").executes(context -> {
                            context.getSource().sendSuccess(() -> Component.literal(status()), false);
                            return 1;
                        }))
                        .then(Commands.literal("check").executes(context -> {
                            context.getSource().sendSuccess(() -> Component.literal(checkNow()), false);
                            return 1;
                        })))));

        Platform.onServerStarted(server -> {
            if (server.isDedicatedServer()) active = new ServerUpdateService(server);
        });
        Platform.onServerTick(server -> {
            if (active != null && active.server == server) active.tick();
        });
        Platform.onServerStopping(server -> {
            if (active == null || active.server != server) return;
            active.close();
            active = null;
        });
    }

    public static void reloadActive() {
        if (active != null) active.reload();
    }

    public static String status() {
        if (active == null) return "専用サーバーでのみ自動更新が動作します。";
        if (active.blockedByPlan) return "前回の更新が未完了です。start-server.ps1 で復旧してください。";
        if (!active.config.enabled) return "サーバー自動更新: 無効";
        if (active.staged) return "更新の準備が完了しました。サーバー終了後に適用します。";
        if (active.schedule.update() == null) return "更新待機なし" + (active.busy ? "（確認中）" : "");
        return "v" + active.schedule.update().latestVersion() + " を検出。更新予定: "
                + Instant.ofEpochMilli(active.schedule.dueAt(active.config.updateDelayMinutes));
    }

    public static String checkNow() {
        if (active == null || !active.config.enabled || active.closed || active.blockedByPlan) return status();
        if (active.busy || active.staged) return status();
        active.nextCheckAt = 0;
        return "更新確認を開始します。結果はサーバーログに表示されます。";
    }

    private void reload() {
        try {
            Files.createDirectories(configFile.getParent());
            if (!Files.exists(configFile)) Files.writeString(configFile, GSON.toJson(new ServerUpdateConfig()));
            ServerUpdateConfig loaded = GSON.fromJson(Files.readString(configFile), ServerUpdateConfig.class);
            if (loaded == null) throw new IOException("Empty update configuration");
            loaded.sanitize();
            // Cancel stale callbacks before applying settings changed with /guildadmin reload.
            generation++;
            if (operation != null) operation.cancel(true);
            busy = false;
            config = loaded;
            retryAt = 0;
            lastRemaining = Long.MAX_VALUE;
            nextCheckAt = config.checkOnStartup ? 0 : System.currentTimeMillis() + config.checkIntervalMinutes * 60_000L;
            if (!config.enabled && staged && !helperStarted) {
                Files.deleteIfExists(plan);
                staged = false;
            }
            GuildQuestsMod.LOGGER.info("[Server Update] Settings loaded: enabled={}, check={} min, delay={} min, shutdown={}",
                    config.enabled, config.checkIntervalMinutes, config.updateDelayMinutes, config.shutdownAfterDownload);
        } catch (Exception e) {
            GuildQuestsMod.LOGGER.error("[Server Update] Invalid configuration; keeping previous settings", e);
        }
    }

    private void tick() {
        if (closed || !config.enabled || blockedByPlan) return;
        long now = System.currentTimeMillis();
        if (staged) {
            if (config.shutdownAfterDownload && now >= retryAt && now >= schedule.dueAt(config.updateDelayMinutes)) requestStop();
            return;
        }
        if (schedule.update() != null) {
            long remaining = Math.max(0, (schedule.dueAt(config.updateDelayMinutes) - now + 999) / 1000);
            if (remaining > 0) {
                for (int threshold : config.countdownSeconds) {
                    if (remaining <= threshold && lastRemaining > threshold) {
                        announce("GuildQuests v" + schedule.update().latestVersion() + " の更新準備まであと " + remaining + " 秒です。"
                                + (config.shutdownAfterDownload ? "準備後にサーバーを保存して停止します。" : "終了時に適用します。"));
                        break;
                    }
                }
            }
            lastRemaining = remaining;
        }
        if (busy) return;
        if (now >= nextCheckAt) {
            check(now);
        } else if (schedule.update() != null && now >= schedule.dueAt(config.updateDelayMinutes) && now >= retryAt) {
            download();
        }
    }

    private void check(long now) {
        busy = true;
        int revision = generation;
        ServerUpdateConfig settings = config;
        nextCheckAt = now + settings.checkIntervalMinutes * 60_000L;
        operation = worker.submit(() -> {
            try {
                ServerReleaseClient.UpdateInfo info = ServerReleaseClient.check(currentVersion(), Platform.getLoaderName(),
                        settings.releaseApiUrl, settings.connectTimeoutSeconds, settings.requestTimeoutSeconds);
                server.execute(() -> {
                    if (closed || generation != revision) return;
                    busy = false;
                    if (info == null) {
                        schedule = new UpdateSchedule(null, 0);
                        try { Files.deleteIfExists(detectionFile); } catch (IOException e) { GuildQuestsMod.LOGGER.warn("[Server Update] Cannot clear detection state", e); }
                        GuildQuestsMod.LOGGER.info("[Server Update] No newer compatible release found");
                        return;
                    }
                    boolean first = schedule.update() == null;
                    schedule.observe(info, System.currentTimeMillis());
                    saveDetection();
                    if (first) {
                        lastRemaining = Long.MAX_VALUE;
                        announce("GuildQuests v" + info.latestVersion() + " を検出しました。" + config.updateDelayMinutes
                                + " 分後に更新を準備します。" + (config.shutdownAfterDownload ? "準備後にサーバーを保存して停止します。" : "適用は次回終了時です。"));
                    }
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                fail(revision, "Release check failed", e, true);
            }
        });
    }

    private void download() {
        Path current = runningModJar();
        if (current == null) {
            GuildQuestsMod.LOGGER.warn("[Server Update] Cannot replace a development/nested Mod. Automatic update skipped.");
            retryAt = System.currentTimeMillis() + config.retryIntervalMinutes * 60_000L;
            return;
        }
        busy = true;
        int revision = generation;
        ServerUpdateConfig settings = config;
        ServerReleaseClient.UpdateInfo update = schedule.update();
        GuildQuestsMod.LOGGER.info("[Server Update] Downloading v{} after the configured delay", update.latestVersion());
        operation = worker.submit(() -> {
            try {
                Path downloaded = ServerReleaseClient.downloadToStage(update, dir, Platform.getLoaderName(),
                        settings.connectTimeoutSeconds, settings.requestTimeoutSeconds, true, Platform::validateUpdateMetadata);
                // Publish the plan only on the server thread, after verifying this operation is still current.
                server.execute(() -> {
                    if (closed || generation != revision || !config.enabled) return;
                    busy = false;
                    try {
                        UpdateInstallation.prepare(current, Platform.getModsDir(), dir, downloaded, update.latestVersion(), Platform.getLoaderName());
                        staged = true;
                        announce("GuildQuests の更新ファイルを検証しました。" + (config.shutdownAfterDownload
                                ? "ワールドを保存してサーバーを停止します。" : "サーバーの次回終了後に適用します。"));
                        if (config.shutdownAfterDownload) requestStop();
                    } catch (Exception e) {
                        retryAt = System.currentTimeMillis() + config.retryIntervalMinutes * 60_000L;
                        GuildQuestsMod.LOGGER.error("[Server Update] Could not prepare installation; server stays running", e);
                    }
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                fail(revision, "Download/validation failed; server stays running", e, false);
            }
        });
    }

    private void requestStop() {
        try {
            if (!helperStarted) {
                UpdateInstallation.launchHelper(plan);
                helperStarted = true;
            }
            Files.writeString(dir.resolve("restart.requested"), "GuildQuests verified update\n");
            // Minecraft's normal shutdown saves players, persistent state, and every world.
            server.halt(false);
        } catch (IOException e) {
            retryAt = System.currentTimeMillis() + config.retryIntervalMinutes * 60_000L;
            GuildQuestsMod.LOGGER.error("[Server Update] Could not launch installer; server stays running", e);
        }
    }

    private void close() {
        closed = true;
        generation++;
        if (operation != null) operation.cancel(true);
        worker.shutdownNow();
        if (staged && !helperStarted) {
            try { UpdateInstallation.launchHelper(plan); }
            catch (IOException e) { GuildQuestsMod.LOGGER.error("[Server Update] Start with start-server.ps1 to apply the staged update", e); }
        }
    }

    private void fail(int revision, String message, Exception error, boolean checkFailure) {
        GuildQuestsMod.LOGGER.warn("[Server Update] " + message, error);
        server.execute(() -> {
            if (closed || generation != revision) return;
            busy = false;
            long retry = System.currentTimeMillis() + config.retryIntervalMinutes * 60_000L;
            if (checkFailure) nextCheckAt = retry;
            retryAt = retry;
        });
    }

    private void saveDetection() {
        try {
            Files.createDirectories(dir);
            Path temporary = dir.resolve("detected-update.json.tmp");
            Files.writeString(temporary, GSON.toJson(new Detection(schedule.update(), schedule.detectedAt())));
            UpdateInstaller.atomicMove(temporary, detectionFile);
        } catch (IOException e) {
            GuildQuestsMod.LOGGER.error("[Server Update] Could not persist detection time", e);
        }
    }

    private void announce(String message) {
        GuildQuestsMod.LOGGER.info("[Server Update] {}", message);
        if (config.notifyPlayers) server.getPlayerList().broadcastSystemMessage(Component.literal("[GuildQuests] " + message), false);
    }
}
