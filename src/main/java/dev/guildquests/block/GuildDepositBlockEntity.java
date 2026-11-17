package dev.guildquests.block;

import dev.guildquests.data.GuildPersistentState;
import dev.guildquests.data.PlayerGuildData;
import dev.guildquests.item.ModItems;
import dev.guildquests.service.GuildService;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

import java.util.UUID;

public class GuildDepositBlockEntity extends BlockEntity implements WorldlyContainer {
    private static final int[] SLOTS = {0};

    private final NonNullList<ItemStack> inventory = NonNullList.withSize(1, ItemStack.EMPTY);
    private UUID ownerId;
    private String ownerName = "";

    private int tickCounter;
    private long lastNoticeAt = -100;
    private long unnotifiedCoins;
    private boolean notifyOnDeposit = true;
    private boolean redstoneOnDeposit = false;

    public GuildDepositBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlocks.GUILD_DEPOSIT_BE, pos, state);
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public void setOwner(UUID id, String name) {
        this.ownerId = id;
        this.ownerName = name == null ? "" : name;
        setChanged();
    }

    public boolean isNotifyOnDeposit() {
        return notifyOnDeposit;
    }

    public void setNotifyOnDeposit(boolean notifyOnDeposit) {
        this.notifyOnDeposit = notifyOnDeposit;
        setChanged();
    }

    public boolean isRedstoneOnDeposit() {
        return redstoneOnDeposit;
    }

    public void setRedstoneOnDeposit(boolean redstoneOnDeposit) {
        this.redstoneOnDeposit = redstoneOnDeposit;
        setChanged();
    }

    /** Emits a ~1s redstone pulse from the block if the setting is enabled. */
    public void pulseRedstone() {
        if (!redstoneOnDeposit) return;
        Level world = getLevel();
        if (world == null || world.isClientSide) return;
        BlockPos pos = getBlockPos();
        BlockState state = world.getBlockState(pos);
        if (!state.is(ModBlocks.GUILD_DEPOSIT)) return;
        world.setBlock(pos, state.setValue(GuildDepositBlock.POWERED, true), 3);
        world.scheduleTick(pos, ModBlocks.GUILD_DEPOSIT, 20);
    }

    /**
     * Server ticker: periodically converts held coins into the owner's account balance.
     * Conversion is skipped while the owner is delinquent so coins wait in the block.
     * Throttled to every 5 ticks; owner notices are throttled to every 100 ticks (5s).
     */
    public static void tick(Level world, BlockPos pos, BlockState state, GuildDepositBlockEntity be) {
        if (world.isClientSide) return;
        if (++be.tickCounter < 5) return;
        be.tickCounter = 0;

        if (be.ownerId == null) return;
        ItemStack stack = be.inventory.get(0);
        if (stack.isEmpty() || !stack.is(ModItems.COIN)) return;

        MinecraftServer server = world.getServer();
        if (server == null) return;
        GuildPersistentState guildState = GuildPersistentState.get(server);
        PlayerGuildData data = guildState.get(be.ownerId);
        if (data.delinquent) return;

        int count = stack.getCount();
        be.setItem(0, ItemStack.EMPTY);
        GuildService.creditCoins(server, be.ownerId, count);
        be.unnotifiedCoins += count;
        be.pulseRedstone();

        if (be.notifyOnDeposit && world.getGameTime() - be.lastNoticeAt >= 100 && be.unnotifiedCoins > 0) {
            ServerPlayer owner = server.getPlayerList().getPlayer(be.ownerId);
            if (owner != null) {
                GuildService.message(owner, be.unnotifiedCoins
                                + " コインが入金ブロックから自動で入金されました。残高: " + data.coins,
                        ChatFormatting.GREEN);
                be.unnotifiedCoins = 0;
                be.lastNoticeAt = world.getGameTime();
            }
        }
    }

    // ------------------------------------------------------------------
    // Inventory
    // ------------------------------------------------------------------

    @Override
    public int getContainerSize() {
        return inventory.size();
    }

    @Override
    public boolean isEmpty() {
        return inventory.get(0).isEmpty();
    }

    @Override
    public ItemStack getItem(int slot) {
        return inventory.get(slot);
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        ItemStack stack = ContainerHelper.removeItem(inventory, slot, amount);
        if (!stack.isEmpty()) setChanged();
        return stack;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        ItemStack stack = ContainerHelper.takeItem(inventory, slot);
        if (!stack.isEmpty()) setChanged();
        return stack;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        inventory.set(slot, stack);
        if (stack.getCount() > getMaxStackSize()) {
            stack.setCount(getMaxStackSize());
        }
        setChanged();
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public void clearContent() {
        inventory.clear();
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return stack.is(ModItems.COIN);
    }

    // ------------------------------------------------------------------
    // WorldlyContainer (hoppers / transfer API)
    // ------------------------------------------------------------------

    @Override
    public int[] getSlotsForFace(Direction side) {
        return SLOTS;
    }

    @Override
    public boolean canPlaceItemThroughFace(int slot, ItemStack stack, Direction side) {
        if (!stack.is(ModItems.COIN)) return false;
        if (ownerId == null) return false;
        Level world = getLevel();
        if (world == null || world.isClientSide) return true;
        MinecraftServer server = world.getServer();
        if (server == null) return true;
        return !GuildPersistentState.get(server).get(ownerId).delinquent;
    }

    @Override
    public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction side) {
        return false;
    }

    // ------------------------------------------------------------------
    // NBT
    // ------------------------------------------------------------------

    @Override
    protected void saveAdditional(CompoundTag nbt, HolderLookup.Provider registries) {
        super.saveAdditional(nbt, registries);
        if (ownerId != null) {
            nbt.putUUID("OwnerId", ownerId);
        }
        nbt.putString("OwnerName", ownerName);
        nbt.putBoolean("NotifyOnDeposit", notifyOnDeposit);
        nbt.putBoolean("RedstoneOnDeposit", redstoneOnDeposit);
        nbt.putLong("UnnotifiedCoins", unnotifiedCoins);
        nbt.putLong("LastNoticeAt", lastNoticeAt);
        ContainerHelper.saveAllItems(nbt, inventory, registries);
    }

    @Override
    public void loadAdditional(CompoundTag nbt, HolderLookup.Provider registries) {
        super.loadAdditional(nbt, registries);
        ownerId = nbt.contains("OwnerId") ? nbt.getUUID("OwnerId") : null;
        ownerName = nbt.getString("OwnerName");
        notifyOnDeposit = !nbt.contains("NotifyOnDeposit") || nbt.getBoolean("NotifyOnDeposit");
        redstoneOnDeposit = nbt.getBoolean("RedstoneOnDeposit");
        unnotifiedCoins = nbt.getLong("UnnotifiedCoins");
        lastNoticeAt = nbt.contains("LastNoticeAt") ? nbt.getLong("LastNoticeAt") : -100;
        inventory.clear();
        ContainerHelper.loadAllItems(nbt, inventory, registries);
    }
}
