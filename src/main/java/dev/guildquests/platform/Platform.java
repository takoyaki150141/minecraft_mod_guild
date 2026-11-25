package dev.guildquests.platform;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

import java.nio.file.Path;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Loader-neutral server-side hooks. The concrete {@link Backend} is bound by each loader's
 * entrypoint before {@code GuildQuestsMod.init()} runs, so common code never sees a loader API.
 */
public final class Platform {
    /** C2S: ask the server to open the guild quest screen (keybinding). */
    public static final ResourceLocation OPEN_GUILD = ResourceLocation.fromNamespaceAndPath("guildquests", "open_guild");

    private static Backend backend;

    private Platform() {
    }

    public static void bind(Backend implementation) {
        backend = implementation;
    }

    private static Backend b() {
        if (backend == null) {
            throw new IllegalStateException("GuildQuests platform backend was not bound");
        }
        return backend;
    }

    public static Path configDir() {
        return b().configDir();
    }

    /** Loader name: "fabric", "forge" or "neoforge". */
    public static String getLoaderName() {
        return b().getLoaderName();
    }

    /** Path to the mods folder. */
    public static Path getModsDir() {
        return b().getModsDir();
    }

    public static String getModVersion() {
        return b().getModVersion();
    }

    public static Path getModJar() {
        return b().getModJar();
    }

    public static void validateUpdateMetadata(java.util.zip.ZipFile jar, String version, boolean dedicatedServer) throws java.io.IOException {
        b().validateUpdateMetadata(jar, version, dedicatedServer);
    }

    public static void onOpenGuildRequest(Consumer<ServerPlayer> handler) {
        b().onOpenGuildRequest(handler);
    }

    public static void onServerStarted(Consumer<MinecraftServer> handler) {
        b().onServerStarted(handler);
    }

    public static void onServerStopped(Consumer<MinecraftServer> handler) {
        b().onServerStopped(handler);
    }

    public static void onServerStopping(Consumer<MinecraftServer> handler) {
        b().onServerStopping(handler);
    }

    public static void onPlayerKill(BiConsumer<ServerPlayer, LivingEntity> handler) {
        b().onPlayerKill(handler);
    }

    public static void onServerTick(Consumer<MinecraftServer> handler) {
        b().onServerTick(handler);
    }

    public static void onCommands(CommandHandler handler) {
        b().onCommands(handler);
    }

    /** Return false from the handler to cancel the damage. */
    public static void onAllowDamage(AllowDamageHandler handler) {
        b().onAllowDamage(handler);
    }

    public static void onEntityDeath(BiConsumer<LivingEntity, DamageSource> handler) {
        b().onEntityDeath(handler);
    }

    /** Called after a server-side player breaks a block. */
    public static void onBlockBreak(BlockBreakHandler handler) {
        b().onBlockBreak(handler);
    }

    /** Return false to cancel the break before it happens (land protection). */
    public static void onCanBreak(BlockPermissionHandler handler) {
        b().onCanBreak(handler);
    }

    /** Return false to cancel the placement before it happens (land protection). */
    public static void onCanPlace(BlockPermissionHandler handler) {
        b().onCanPlace(handler);
    }

    /** Return false to cancel a right-click block interaction (land protection). */
    public static void onCanUse(BlockUsePermissionHandler handler) {
        b().onCanUse(handler);
    }

    public static boolean canSend(ServerPlayer player, ResourceLocation channel) {
        return b().canSend(player, channel);
    }

    public static void send(ServerPlayer player, ResourceLocation channel, FriendlyByteBuf buf) {
        b().send(player, channel, buf);
    }

    @FunctionalInterface
    public interface CommandHandler {
        void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext registryAccess);
    }

    @FunctionalInterface
    public interface AllowDamageHandler {
        boolean allow(LivingEntity entity, DamageSource source, float amount);
    }

    @FunctionalInterface
    public interface BlockBreakHandler {
        void accept(ServerPlayer player, BlockState state);
    }

    @FunctionalInterface
    public interface BlockPermissionHandler {
        boolean allow(ServerPlayer player, net.minecraft.core.BlockPos pos, BlockState state);
    }

    @FunctionalInterface
    public interface BlockUsePermissionHandler {
        boolean allow(ServerPlayer player, net.minecraft.core.BlockPos pos);
    }

    public interface Backend {
        Path configDir();

        String getLoaderName();

        Path getModsDir();

        String getModVersion();

        Path getModJar();

        void validateUpdateMetadata(java.util.zip.ZipFile jar, String version, boolean dedicatedServer) throws java.io.IOException;

        void onOpenGuildRequest(Consumer<ServerPlayer> handler);

        void onServerStarted(Consumer<MinecraftServer> handler);

        void onServerStopped(Consumer<MinecraftServer> handler);

        void onServerStopping(Consumer<MinecraftServer> handler);

        void onPlayerKill(BiConsumer<ServerPlayer, LivingEntity> handler);

        void onServerTick(Consumer<MinecraftServer> handler);

        void onCommands(CommandHandler handler);

        void onAllowDamage(AllowDamageHandler handler);

        void onEntityDeath(BiConsumer<LivingEntity, DamageSource> handler);

        void onBlockBreak(BlockBreakHandler handler);

        void onCanBreak(BlockPermissionHandler handler);

        void onCanPlace(BlockPermissionHandler handler);

        void onCanUse(BlockUsePermissionHandler handler);

        boolean canSend(ServerPlayer player, ResourceLocation channel);

        void send(ServerPlayer player, ResourceLocation channel, FriendlyByteBuf buf);
    }
}
