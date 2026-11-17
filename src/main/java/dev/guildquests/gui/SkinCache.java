package dev.guildquests.gui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.guildquests.platform.Platform;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Caches player skin textures (the base64 {@code textures} property) so heads keep their skin even
 * when the player is offline. Online players are remembered from their live GameProfile; anyone
 * else is fetched once from the Mojang session server and stored in {@code config/guildquests/skins.json}.
 */
public final class SkinCache {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("guildquests");
    private static final Map<String, String> TEXTURES = new ConcurrentHashMap<>();
    private static final java.util.Set<String> REQUESTED = ConcurrentHashMap.newKeySet();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "GuildQuests-SkinFetch");
        thread.setDaemon(true);
        return thread;
    });
    private static volatile boolean loaded;

    private SkinCache() {
    }

    /**
     * The skin texture for a player, or null when unknown. An online player's live profile is used
     * and remembered; otherwise the cache is consulted and a one-off background fetch is queued.
     */
    public static String texture(MinecraftServer server, UUID id, String name) {
        if (id == null) {
            return null;
        }
        String live = liveTexture(server, id);
        if (live != null) {
            remember(id, live);
            return live;
        }
        load();
        String cached = TEXTURES.get(id.toString());
        if (cached != null) {
            return cached;
        }
        fetchAsync(id);
        return null;
    }

    /** Stores a texture and persists the cache. */
    public static void remember(UUID id, String texture) {
        if (id == null || texture == null || texture.isEmpty()) {
            return;
        }
        String previous = TEXTURES.put(id.toString(), texture);
        if (!texture.equals(previous)) {
            save();
        }
    }

    private static String liveTexture(MinecraftServer server, UUID id) {
        if (server == null) {
            return null;
        }
        try {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player != null) {
                var properties = player.getGameProfile().getProperties().get("textures");
                if (properties != null) {
                    for (var property : properties) {
                        String value = property.value();
                        if (value != null && !value.isEmpty()) {
                            return value;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static void fetchAsync(UUID id) {
        String key = id.toString();
        if (!REQUESTED.add(key)) {
            return;
        }
        WORKER.submit(() -> {
            try {
                Thread.sleep(1000L); // stay gentle with the Mojang rate limit
                String texture = fetch(id);
                if (texture != null) {
                    remember(id, texture);
                    LOGGER.info("[Skin] Cached skin for {}", key);
                } else {
                    REQUESTED.remove(key); // allow a later retry
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                REQUESTED.remove(key);
                LOGGER.debug("[Skin] Fetch failed for {}", key, e);
            }
        });
    }

    private static String fetch(UUID id) throws Exception {
        String uuid = id.toString().replace("-", "");
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://sessionserver.mojang.com/session/minecraft/profile/" + uuid))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", "GuildQuests-Mod/" + Platform.getModVersion())
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            return null;
        }
        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        if (!root.has("properties")) {
            return null;
        }
        for (JsonElement element : root.getAsJsonArray("properties")) {
            JsonObject property = element.getAsJsonObject();
            if (property.has("name") && "textures".equals(property.get("name").getAsString()) && property.has("value")) {
                String value = property.get("value").getAsString();
                if (value != null && !value.isEmpty()) {
                    return value;
                }
            }
        }
        return null;
    }

    private static synchronized void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        try {
            Path file = file();
            if (!Files.isRegularFile(file)) {
                return;
            }
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            for (var entry : root.entrySet()) {
                if (entry.getValue().isJsonPrimitive()) {
                    TEXTURES.put(entry.getKey(), entry.getValue().getAsString());
                }
            }
            LOGGER.info("[Skin] Loaded {} cached skins", TEXTURES.size());
        } catch (Exception e) {
            LOGGER.warn("[Skin] Could not load skin cache", e);
        }
    }

    private static synchronized void save() {
        try {
            Path file = file();
            Files.createDirectories(file.getParent());
            JsonObject root = new JsonObject();
            for (var entry : TEXTURES.entrySet()) {
                root.addProperty(entry.getKey(), entry.getValue());
            }
            Files.writeString(file, root.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("[Skin] Could not save skin cache", e);
        }
    }

    private static Path file() {
        return Platform.configDir().resolve("skins.json");
    }
}
