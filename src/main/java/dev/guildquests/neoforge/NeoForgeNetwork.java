package dev.guildquests.neoforge;

import dev.guildquests.gui.GuildMenu;
import dev.guildquests.service.LandService;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.function.Consumer;

/**
 * The single NeoForge play channel, shared by guild, boss pin and GUI packets.
 */
public final class NeoForgeNetwork {
    public static final String PROTOCOL = "5";

    private static Consumer<ServerPlayer> openGuildHandler;
    private static BossPinSink bossPinSink;

    private NeoForgeNetwork() {
    }

    /** Must be called during mod construction, before the network registry is locked. */
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(PROTOCOL).optional();
        registrar.playToServer(OpenGuildC2S.TYPE, OpenGuildC2S.STREAM_CODEC, NeoForgeNetwork::handleOpenGuild);
        registrar.playToClient(BossPinS2C.TYPE, BossPinS2C.STREAM_CODEC, NeoForgeNetwork::handleBossPin);
        registrar.playToServer(GuiAmountC2S.TYPE, GuiAmountC2S.STREAM_CODEC, NeoForgeNetwork::handleGuiAmount);
        registrar.playToServer(BuyLandC2S.TYPE, BuyLandC2S.STREAM_CODEC, NeoForgeNetwork::handleBuyLand);
    }

    private static void handleOpenGuild(OpenGuildC2S payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer sender && openGuildHandler != null) {
                openGuildHandler.accept(sender);
            }
        });
    }

    private static void handleBossPin(BossPinS2C payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (bossPinSink != null) {
                bossPinSink.onBossPin(payload.x(), payload.y(), payload.z(), payload.lifetime());
            }
        });
    }

    private static void handleGuiAmount(GuiAmountC2S payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer sender) || sender.isRemoved() || payload.amount() < 0) {
                return;
            }
            if (sender.containerMenu instanceof GuildMenu menu && menu.containerId == payload.containerId()
                    && menu.stillValid(sender) && menu.setGuiAmount(payload.amount())) {
                menu.broadcastChanges();
            }
        });
    }

    public static void setOpenGuildHandler(Consumer<ServerPlayer> handler) {
        openGuildHandler = handler;
    }

    public static void setBossPinSink(BossPinSink sink) {
        bossPinSink = sink;
    }

    public static void sendBossPin(ServerPlayer player, int x, int y, int z, int lifetime) {
        PacketDistributor.sendToPlayer(player, new BossPinS2C(x, y, z, lifetime));
    }

    public static void sendGuiAmount(int containerId, long amount) {
        PacketDistributor.sendToServer(new GuiAmountC2S(containerId, amount));
    }

    private static void handleBuyLand(BuyLandC2S payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                LandService.buy(player, payload.chunkX(), payload.chunkZ());
            }
        });
    }

    /** C2S: buy one chunk from the land map. */
    public static void sendBuyLand(int chunkX, int chunkZ) {
        PacketDistributor.sendToServer(new BuyLandC2S(chunkX, chunkZ));
    }

    public record BuyLandC2S(int chunkX, int chunkZ) implements CustomPacketPayload {
        public static final Type<BuyLandC2S> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath("guildquests", "buy_land"));
        public static final StreamCodec<RegistryFriendlyByteBuf, BuyLandC2S> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, BuyLandC2S::chunkX,
                        ByteBufCodecs.VAR_INT, BuyLandC2S::chunkZ,
                        BuyLandC2S::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record GuiAmountC2S(int containerId, long amount) implements CustomPacketPayload {
        public static final Type<GuiAmountC2S> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath("guildquests", "gui_amount"));
        public static final StreamCodec<RegistryFriendlyByteBuf, GuiAmountC2S> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, GuiAmountC2S::containerId,
                        ByteBufCodecs.VAR_LONG, GuiAmountC2S::amount,
                        GuiAmountC2S::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C2S: the client asked to open the guild quest screen. */
    public record OpenGuildC2S() implements CustomPacketPayload {
        public static final Type<OpenGuildC2S> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath("guildquests", "open_guild"));
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenGuildC2S> STREAM_CODEC =
                StreamCodec.unit(new OpenGuildC2S());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** S2C: beacon-style boss pin marker. lifetime 0 clears the pin. */
    public record BossPinS2C(int x, int y, int z, int lifetime) implements CustomPacketPayload {
        public static final Type<BossPinS2C> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath("guildquests", "boss_pin"));
        public static final StreamCodec<RegistryFriendlyByteBuf, BossPinS2C> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, BossPinS2C::x,
                        ByteBufCodecs.VAR_INT, BossPinS2C::y,
                        ByteBufCodecs.VAR_INT, BossPinS2C::z,
                        ByteBufCodecs.VAR_INT, BossPinS2C::lifetime,
                        BossPinS2C::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Client-side sink for boss pin packets; kept free of client classes so the server may load this class. */
    public interface BossPinSink {
        void onBossPin(int x, int y, int z, int lifetime);
    }
}
