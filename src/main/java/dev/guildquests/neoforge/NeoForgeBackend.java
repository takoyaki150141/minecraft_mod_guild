package dev.guildquests.neoforge;

import dev.guildquests.platform.Platform;
import dev.guildquests.service.BossEvent;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * NeoForge implementation of the loader-neutral {@link Platform.Backend}.
 * <p>
 * Each hook keeps a list of handlers (like Fabric's event callbacks), so registering more than
 * one handler per hook accumulates them instead of overwriting — e.g. the boss event and the
 * guild core both register a server tick.
 */
public final class NeoForgeBackend implements Platform.Backend {
    private final List<Consumer<MinecraftServer>> serverStarted = new ArrayList<>();
    private final List<Consumer<MinecraftServer>> serverStopped = new ArrayList<>();
    private final List<Consumer<MinecraftServer>> serverStopping = new ArrayList<>();
    private final List<Consumer<MinecraftServer>> serverTick = new ArrayList<>();
    private final List<Platform.CommandHandler> commands = new ArrayList<>();
    private final List<Platform.AllowDamageHandler> allowDamage = new ArrayList<>();
    private final List<BiConsumer<ServerPlayer, LivingEntity>> playerKill = new ArrayList<>();
    private final List<BiConsumer<LivingEntity, DamageSource>> entityDeath = new ArrayList<>();
    private final List<Platform.BlockBreakHandler> blockBreak = new ArrayList<>();
    private final List<Platform.BlockPermissionHandler> canBreak = new ArrayList<>();
    private final List<Platform.BlockPermissionHandler> canPlace = new ArrayList<>();
    private final List<Platform.BlockUsePermissionHandler> canUse = new ArrayList<>();
    private final List<Platform.EntityUsePermissionHandler> canUseEntity = new ArrayList<>();
    private final List<Consumer<ServerPlayer>> playerTick = new ArrayList<>();
    private final List<Consumer<ServerPlayer>> playerLeave = new ArrayList<>();

    /** Wire the stored handlers up to the NeoForge event bus. Safe to call once at construction. */
    public void registerNeoForgeEvents() {
        NeoForge.EVENT_BUS.addListener(this::handleServerStarted);
        NeoForge.EVENT_BUS.addListener(this::handleServerStopped);
        NeoForge.EVENT_BUS.addListener(this::handleServerStopping);
        NeoForge.EVENT_BUS.addListener(this::handleServerTick);
        NeoForge.EVENT_BUS.addListener(this::handleCommands);
        NeoForge.EVENT_BUS.addListener(this::handleLivingAttack);
        NeoForge.EVENT_BUS.addListener(this::handleLivingDeath);
        NeoForge.EVENT_BUS.addListener(this::handleBlockBreak);
        NeoForge.EVENT_BUS.addListener(this::handleBlockPlace);
        NeoForge.EVENT_BUS.addListener(this::handleBlockUse);
        NeoForge.EVENT_BUS.addListener(this::handleEntityInteract);
        NeoForge.EVENT_BUS.addListener(this::handleEntityInteractSpecific);
        NeoForge.EVENT_BUS.addListener(this::handlePlayerTick);
        NeoForge.EVENT_BUS.addListener(this::handlePlayerLeave);
    }

    private void handleBlockBreak(BlockEvent.BreakEvent event) {
        if (event.getPlayer() instanceof ServerPlayer player) {
            for (Platform.BlockPermissionHandler handler : canBreak) {
                if (!handler.allow(player, event.getPos(), event.getState())) {
                    event.setCanceled(true);
                    return;
                }
            }
            for (Platform.BlockBreakHandler handler : blockBreak) {
                handler.accept(player, event.getState());
            }
        }
    }

    private void handleBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        for (Platform.BlockPermissionHandler handler : canPlace) {
            if (!handler.allow(player, event.getPos(), event.getPlacedBlock())) {
                event.setCanceled(true);
                return;
            }
        }
    }

    private void handleBlockUse(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        for (Platform.BlockUsePermissionHandler handler : canUse) {
            if (!handler.allow(player, event.getPos())) {
                event.setCanceled(true);
                event.setCancellationResult(net.minecraft.world.InteractionResult.FAIL);
                return;
            }
        }
    }

    private void handleEntityInteract(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.EntityInteract event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        cancelEntityUse(player, event.getTarget(), () -> {
            event.setCanceled(true);
            event.setCancellationResult(net.minecraft.world.InteractionResult.FAIL);
        });
    }

    private void handleEntityInteractSpecific(
            net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.EntityInteractSpecific event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        cancelEntityUse(player, event.getTarget(), () -> {
            event.setCanceled(true);
            event.setCancellationResult(net.minecraft.world.InteractionResult.FAIL);
        });
    }

    private void cancelEntityUse(ServerPlayer player, net.minecraft.world.entity.Entity target, Runnable cancel) {
        for (Platform.EntityUsePermissionHandler handler : canUseEntity) {
            if (!handler.allow(player, target)) {
                cancel.run();
                return;
            }
        }
    }

    private void handlePlayerTick(net.neoforged.neoforge.event.tick.PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        for (Consumer<ServerPlayer> handler : playerTick) {
            handler.accept(player);
        }
    }

    private void handlePlayerLeave(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        for (Consumer<ServerPlayer> handler : playerLeave) {
            handler.accept(player);
        }
    }

    private void handleServerStarted(ServerStartedEvent event) {
        for (Consumer<MinecraftServer> handler : serverStarted) {
            handler.accept(event.getServer());
        }
    }

    private void handleServerStopped(ServerStoppedEvent event) {
        for (Consumer<MinecraftServer> handler : serverStopped) {
            handler.accept(event.getServer());
        }
    }

    private void handleServerStopping(ServerStoppingEvent event) {
        for (Consumer<MinecraftServer> handler : serverStopping) handler.accept(event.getServer());
    }

    private void handleServerTick(ServerTickEvent.Post event) {
        for (Consumer<MinecraftServer> handler : serverTick) {
            handler.accept(event.getServer());
        }
    }

    private void handleCommands(RegisterCommandsEvent event) {
        for (Platform.CommandHandler handler : commands) {
            handler.register(event.getDispatcher(), event.getBuildContext());
        }
    }

    private void handleLivingAttack(LivingIncomingDamageEvent event) {
        for (Platform.AllowDamageHandler handler : allowDamage) {
            if (!handler.allow(event.getEntity(), event.getSource(), event.getAmount())) {
                event.setCanceled(true);
                return;
            }
        }
    }

    private void handleLivingDeath(LivingDeathEvent event) {
        for (BiConsumer<LivingEntity, DamageSource> handler : entityDeath) {
            handler.accept(event.getEntity(), event.getSource());
        }
        Entity killer = event.getSource().getEntity();
        if (killer instanceof ServerPlayer player) {
            for (BiConsumer<ServerPlayer, LivingEntity> handler : playerKill) {
                handler.accept(player, event.getEntity());
            }
        }
    }

    @Override
    public Path configDir() {
        return FMLPaths.CONFIGDIR.get().resolve("guildquests");
    }

    @Override
    public String getLoaderName() {
        return "neoforge";
    }

    @Override
    public Path getModsDir() {
        return FMLPaths.MODSDIR.get();
    }

    @Override
    public String getModVersion() {
        return ModList.get().getModContainerById("guildquests").orElseThrow()
                .getModInfo().getVersion().toString();
    }

    @Override
    public Path getModJar() {
        Path path = ModList.get().getModFileById("guildquests").getFile().getFilePath();
        if (!java.nio.file.Files.isRegularFile(path)) return null;
        // Never hand a modpack/JiJ host JAR to the updater; only replace our own release asset.
        String name = path.getFileName().toString();
        return name.matches("guildquests_.*-1\\.21\\.1_.*\\.jar") ? path : null;
    }

    @Override
    public void validateUpdateMetadata(java.util.zip.ZipFile jar, String version, boolean dedicatedServer) throws java.io.IOException {
        java.util.Map<String, String> installed = new java.util.HashMap<>();
        for (var mod : ModList.get().getMods()) installed.put(mod.getModId(), mod.getVersion().toString());
        installed.put("fml", net.neoforged.fml.loading.FMLLoader.versionInfo().fmlVersion());
        installed.put("java", Integer.toString(Runtime.version().feature()));
        dev.guildquests.update.NeoForgeUpdateMetadata.validate(jar, version, dedicatedServer, installed);
    }

    @Override
    public void onOpenGuildRequest(Consumer<ServerPlayer> handler) {
        NeoForgeNetwork.setOpenGuildHandler(handler);
    }

    @Override
    public void onServerStarted(Consumer<MinecraftServer> handler) {
        serverStarted.add(handler);
    }

    @Override
    public void onServerStopped(Consumer<MinecraftServer> handler) {
        serverStopped.add(handler);
    }

    @Override
    public void onServerStopping(Consumer<MinecraftServer> handler) {
        serverStopping.add(handler);
    }

    @Override
    public void onPlayerKill(BiConsumer<ServerPlayer, LivingEntity> handler) {
        playerKill.add(handler);
    }

    @Override
    public void onServerTick(Consumer<MinecraftServer> handler) {
        serverTick.add(handler);
    }

    @Override
    public void onCommands(Platform.CommandHandler handler) {
        commands.add(handler);
    }

    @Override
    public void onAllowDamage(Platform.AllowDamageHandler handler) {
        allowDamage.add(handler);
    }

    @Override
    public void onEntityDeath(BiConsumer<LivingEntity, DamageSource> handler) {
        entityDeath.add(handler);
    }

    @Override
    public void onBlockBreak(Platform.BlockBreakHandler handler) {
        blockBreak.add(handler);
    }

    @Override
    public void onCanBreak(Platform.BlockPermissionHandler handler) {
        canBreak.add(handler);
    }

    @Override
    public void onCanPlace(Platform.BlockPermissionHandler handler) {
        canPlace.add(handler);
    }

    @Override
    public void onCanUse(Platform.BlockUsePermissionHandler handler) {
        canUse.add(handler);
    }

    @Override
    public void onCanUseEntity(Platform.EntityUsePermissionHandler handler) {
        canUseEntity.add(handler);
    }

    @Override
    public void onPlayerTick(Consumer<ServerPlayer> handler) {
        playerTick.add(handler);
    }

    @Override
    public void onPlayerLeave(Consumer<ServerPlayer> handler) {
        playerLeave.add(handler);
    }

    @Override
    public boolean canSend(ServerPlayer player, ResourceLocation channel) {
        if (!BossEvent.BOSS_PIN.equals(channel)) {
            return false;
        }
        return NetworkRegistry.hasChannel(player.connection.getConnection(), ConnectionProtocol.PLAY, NeoForgeNetwork.BossPinS2C.TYPE.id());
    }

    @Override
    public void send(ServerPlayer player, ResourceLocation channel, FriendlyByteBuf buf) {
        if (!BossEvent.BOSS_PIN.equals(channel)) {
            throw new IllegalArgumentException("Unknown guildquests channel: " + channel);
        }
        int x = buf.readVarInt();
        int y = buf.readVarInt();
        int z = buf.readVarInt();
        int lifetime = buf.readVarInt();
        NeoForgeNetwork.sendBossPin(player, x, y, z, lifetime);
    }
}
