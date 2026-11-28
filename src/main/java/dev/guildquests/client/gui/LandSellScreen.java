package dev.guildquests.client.gui;

import dev.guildquests.gui.GuildClientMenu;
import dev.guildquests.gui.GuildItems;
import dev.guildquests.gui.LandView;
import dev.guildquests.neoforge.NeoForgeNetwork;
import dev.guildquests.util.Numbers;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.MenuAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Sells one owned chunk: either to the system at a reduced price or to another player. */
public final class LandSellScreen extends Screen implements MenuAccess<GuildClientMenu> {
    private final GuildClientMenu menu;
    private final Screen parent;
    private final int index;
    private CompoundTag view = new CompoundTag();
    private int chunkX;
    private int chunkZ;
    private long instantPrice;
    private long listedPrice;
    private boolean confirmSell;
    private EditBox priceField;
    private Button sellButton;
    private Button listButton;
    private Button cancelButton;

    public LandSellScreen(GuildClientMenu menu, Screen parent, Component title, int index) {
        super(title);
        this.menu = menu;
        this.parent = parent;
        this.index = index;
    }

    @Override public GuildClientMenu getMenu() { return menu; }

    @Override public boolean isPauseScreen() { return false; }

    @Override protected void init() {
        readView();
        layout();
    }

    @Override public void tick() {
        if (minecraft.player == null || !minecraft.player.isAlive() || minecraft.player.isRemoved()) {
            back();
            return;
        }
        long previousInstant = instantPrice;
        long previousListed = listedPrice;
        readView();
        if (previousInstant != instantPrice || previousListed != listedPrice) {
            if (priceField != null && priceField.getValue().isBlank()) {
                priceField.setValue(Long.toString(Math.max(1, instantPrice)));
            }
            updateButtons();
        }
    }

    private void readView() {
        CompoundTag tag = GuildItems.tag(menu.getSlot(0).getItem());
        view = tag.contains(LandView.TAG) ? tag.getCompound(LandView.TAG) : new CompoundTag();
        int radius = Math.max(1, Math.min(8, view.getInt("radius")));
        int side = radius * 2 + 1;
        chunkX = view.getInt("cx") + index % side - radius;
        chunkZ = view.getInt("cz") + index / side - radius;

        long[] sellPrices = view.getLongArray("sellPrices");
        instantPrice = index >= 0 && index < sellPrices.length ? sellPrices[index] : 0;

        listedPrice = 0;
        ListTag listings = view.getList("listings", Tag.TAG_COMPOUND);
        for (int i = 0; i < listings.size(); i++) {
            CompoundTag row = listings.getCompound(i);
            if (row.getInt("index") == index) listedPrice = row.getLong("price");
        }
    }

    private void layout() {
        clearWidgets();
        int margin = Math.max(8, Math.min(24, width / 40));
        int left = margin;
        int contentWidth = Math.max(160, width - margin * 2);
        int centerX = width / 2;
        int y = 92;

        sellButton = addRenderableWidget(Button.builder(Component.literal("即時換金"), b -> sell())
                .bounds(left, 66, contentWidth, 20).build());

        int fieldWidth = Math.max(80, contentWidth - 110);
        priceField = new EditBox(font, left, y, fieldWidth, 20, Component.literal("価格"));
        priceField.setFilter(value -> value.matches("\\d{0,10}"));
        priceField.setMaxLength(10);
        priceField.setValue(Long.toString(Math.max(1, listedPrice > 0 ? listedPrice : instantPrice)));
        addRenderableWidget(priceField);

        listButton = addRenderableWidget(Button.builder(Component.literal("出品する"), b -> list())
                .bounds(left + fieldWidth + 6, y, Math.max(72, contentWidth - fieldWidth - 6), 20).build());
        cancelButton = addRenderableWidget(Button.builder(Component.literal("出品を取り消す"), b -> cancel())
                .bounds(left, y, contentWidth, 20).build());

        addRenderableWidget(Button.builder(Component.literal("戻る"), b -> back())
                .bounds(centerX - Math.min(80, contentWidth / 2), Math.min(height - 26, y + 34),
                        Math.min(160, contentWidth), 20).build());
        updateButtons();
    }

    private void updateButtons() {
        boolean listed = listedPrice > 0;
        if (sellButton != null) {
            sellButton.setMessage(Component.literal(confirmSell
                    ? "もう一度押すと売却（" + Numbers.format(instantPrice) + " GC）"
                    : "即時換金（" + Numbers.format(instantPrice) + " GC）"));
            sellButton.active = !listed;
        }
        if (priceField != null) priceField.setVisible(!listed);
        if (listButton != null) {
            listButton.visible = !listed;
            listButton.active = !listed;
        }
        if (cancelButton != null) {
            cancelButton.visible = listed;
            cancelButton.active = listed;
        }
    }

    private void sell() {
        if (listedPrice > 0) return;
        if (!confirmSell) {
            confirmSell = true;
            updateButtons();
            return;
        }
        NeoForgeNetwork.sendLandSale(0, chunkX, chunkZ, 0);
        back();
    }

    private void list() {
        long price;
        try {
            price = Long.parseLong(priceField.getValue());
        } catch (NumberFormatException e) {
            price = 0;
        }
        if (price <= 0) {
            priceField.setValue("1");
            return;
        }
        NeoForgeNetwork.sendLandSale(1, chunkX, chunkZ, price);
        back();
    }

    private void cancel() {
        NeoForgeNetwork.sendLandSale(2, chunkX, chunkZ, 0);
        back();
    }

    private void back() {
        minecraft.setScreen(parent);
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderTransparentBackground(graphics);
        int margin = Math.max(8, Math.min(24, width / 40));
        graphics.drawString(font, "土地の売却", margin, 10, 0xFFFFFF);
        graphics.drawString(font, fit("チャンク (" + chunkX + ", " + chunkZ + ")", width - margin * 2),
                margin, 22, 0xA0A0A0);
        graphics.drawString(font, "システムに売る（購入価格の " + Numbers.format(instantPrice) + " GC）",
                margin, 50, 0xFFE8E8B0);
        if (listedPrice > 0) {
            graphics.drawString(font, "出品中: " + Numbers.format(listedPrice) + " GC",
                    margin, 74, 0xFFD700);
        } else {
            graphics.drawString(font, "プレイヤーに売る（価格を入力して出品）", margin, 74, 0xA0A0A0);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private String fit(String value, int maxWidth) {
        if (font.width(value) <= maxWidth) return value;
        return font.plainSubstrByWidth(value, Math.max(0, maxWidth - font.width("…"))) + "…";
    }

    @Override public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            back();
            return true;
        }
        return super.keyPressed(key, scanCode, modifiers);
    }
}
