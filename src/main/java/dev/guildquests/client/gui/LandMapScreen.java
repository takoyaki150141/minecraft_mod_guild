package dev.guildquests.client.gui;

import dev.guildquests.gui.GuildClientMenu;
import dev.guildquests.gui.GuildItems;
import dev.guildquests.gui.LandView;
import dev.guildquests.neoforge.NeoForgeNetwork;
import dev.guildquests.util.Numbers;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.MenuAccess;
import net.minecraft.nbt.CompoundTag;import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import org.lwjgl.glfw.GLFW;

/** Chunk map centered on the player; the server owns all purchase decisions. */
public final class LandMapScreen extends GuildMenuScreen {
    private static final int COLOR_UNOWNED = 0x80405A40;
    private static final int COLOR_SELF = 0x904060C0;
    private static final int COLOR_OTHER = 0x90602A2A;
    private static final int BORDER_UNOWNED = 0xFF5F7F5F;
    private static final int BORDER_SELF = 0xFF6FA8FF;
    private static final int BORDER_OTHER = 0xFFE08080;
    private static final int COLOR_SELECTED = 0xFFFFD700;

    private final GuildClientMenu menu;
    private CompoundTag view = new CompoundTag();
    private LandMapLayout layout;
    private int selected = -1;
    private Button buyButton;

    public LandMapScreen(GuildClientMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.menu = menu;
    }

    @Override public GuildClientMenu getMenu() { return menu; }

    @Override public boolean isPauseScreen() { return false; }

    @Override protected void init() {
        readView();
        rebuild();
    }

    @Override public void tick() {
        if (minecraft.player == null || !minecraft.player.isAlive() || minecraft.player.isRemoved()) {
            onClose();
            return;
        }
        int previousRadius = view.getInt("radius");
        readView();
        if (layout == null || previousRadius != view.getInt("radius")) rebuild();
        else updateBuyButton();
    }

    private void readView() {
        CompoundTag tag = GuildItems.tag(menu.getSlot(0).getItem());
        view = tag.contains(LandView.TAG) ? tag.getCompound(LandView.TAG) : new CompoundTag();
    }

    private void rebuild() {
        clearWidgets();
        selected = -1;
        layout = LandMapLayout.of(width, height, view.getInt("radius"));
        int buttonY = Math.min(height - 26, layout.gridBottom() + 18);
        int half = Math.min(150, (layout.gridWidth() - 8) / 2);
        int center = width / 2;
        addRenderableWidget(Button.builder(Component.literal("戻る"), b -> back())
                .bounds(center - half - 4 - 80, buttonY, 80, 20).build());
        buyButton = addRenderableWidget(Button.builder(Component.literal("購入"), b -> buySelected())
                .bounds(center - half, buttonY, half * 2, 20).build());
        addRenderableWidget(Button.builder(Component.literal("閉じる"), b -> onClose())
                .bounds(center + half + 4, buttonY, 80, 20).build());
        updateBuyButton();
    }

    private void back() {
        if (minecraft.player != null && minecraft.gameMode != null) {
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, 0);
        }
    }

    private void buySelected() {
        if (selected < 0 || cellState(selected) != LandView.UNOWNED || layout == null) return;
        int[] chunk = chunkAt(selected);
        NeoForgeNetwork.sendBuyLand(chunk[0], chunk[1]);
    }

    private void updateBuyButton() {
        if (buyButton == null || layout == null) return;
        long price = view.getLong("price");
        long coins = view.getLong("coins");
        int owned = view.getInt("owned");
        int max = view.getInt("max");
        boolean unowned = selected >= 0 && cellState(selected) == LandView.UNOWNED;
        boolean withinLimit = max <= 0 || owned < max;
        boolean affordable = price <= 0 || coins >= price;
        buyButton.active = unowned && withinLimit && affordable;
        buyButton.setMessage(Component.literal(price > 0
                ? "購入 (" + Numbers.format(price) + " GC)"
                : (price == 0 ? "購入 (無料)" : "購入")));
    }

    private byte cellState(int index) {
        byte[] cells = view.getByteArray("cells");
        return index >= 0 && index < cells.length ? cells[index] : LandView.UNOWNED;
    }

    private int[] chunkAt(int index) {
        int side = layout.side();
        return new int[]{view.getInt("cx") + index % side - layout.radius(),
                view.getInt("cz") + index / side - layout.radius()};
    }

    private boolean hasView() {
        return view.contains("cells");
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderTransparentBackground(graphics);
        int titleY = Math.max(8, layout.top() - 34);
        graphics.drawString(font, "土地保護 - チャンク購入", layout.left(), titleY, 0xFFFFFF);
        String dim = view.getString("dim");
        if (!dim.isBlank()) {
            graphics.drawString(font, fit(readableDimension(dim), layout.gridWidth()), layout.left(), titleY + 12, 0xA0A0A0);
        }
        if (!hasView()) {
            graphics.drawCenteredString(font, "読み込み中…", width / 2, layout.top() + 12, 0xCCCCCC);
        } else {
            renderGrid(graphics, mouseX, mouseY);
        }
        drawInfo(graphics);
        int hovered = layout.indexAt(mouseX, mouseY);
        for (var child : children()) {
            if (child instanceof net.minecraft.client.gui.components.Renderable widget) {
                widget.render(graphics, mouseX, mouseY, partialTick);
            }
        }
        if (hovered >= 0 && hasView()) {
            int[] chunk = chunkAt(hovered);
            String state = switch (cellState(hovered)) {
                case LandView.SELF -> "所有: 自分";
                case LandView.OTHER -> "所有: " + view.getCompound("owners").getString(Integer.toString(hovered));
                default -> "未購入: " + Numbers.format(view.getLong("price")) + " GC";
            };
            graphics.renderTooltip(font, Component.literal("チャンク (" + chunk[0] + ", " + chunk[1] + ")  " + state),
                    mouseX, mouseY);
        }
    }

    private void renderGrid(GuiGraphics graphics, int mouseX, int mouseY) {
        int hovered = layout.indexAt(mouseX, mouseY);
        int side = layout.side();
        for (int index = 0; index < side * side; index++) {
            int x = layout.cellX(index);
            int y = layout.cellY(index);
            int right = x + layout.cell();
            int bottom = y + layout.cell();
            boolean isSelected = index == selected;
            boolean isHovered = index == hovered;
            int fill = switch (cellState(index)) {
                case LandView.SELF -> COLOR_SELF;
                case LandView.OTHER -> COLOR_OTHER;
                default -> COLOR_UNOWNED;
            };
            int border = switch (cellState(index)) {
                case LandView.SELF -> BORDER_SELF;
                case LandView.OTHER -> BORDER_OTHER;
                default -> BORDER_UNOWNED;
            };
            graphics.fill(x, y, right, bottom, fill);
            graphics.fill(x, y, right, y + 1, border);
            graphics.fill(x, bottom - 1, right, bottom, border);
            graphics.fill(x, y, x + 1, bottom, border);
            graphics.fill(right - 1, y, right, bottom, border);
            if (isHovered && !isSelected) {
                graphics.fill(x, y, right, bottom, 0x30FFFFFF);
            }
            if (isSelected) {
                graphics.fill(x, y, right, y + 2, COLOR_SELECTED);
                graphics.fill(x, bottom - 2, right, bottom, COLOR_SELECTED);
                graphics.fill(x, y, x + 2, bottom, COLOR_SELECTED);
                graphics.fill(right - 2, y, right, bottom, COLOR_SELECTED);
            }
        }
        // Mark the player's own chunk (the grid center).
        int centerIndex = layout.radius() * side + layout.radius();
        int cx = layout.cellX(centerIndex) + layout.cell() / 2;
        int cy = layout.cellY(centerIndex) + layout.cell() / 2;
        graphics.fill(cx - 2, cy - 2, cx + 2, cy + 2, 0xFFFFFFFF);
    }

    private void drawInfo(GuiGraphics graphics) {
        int y = layout.gridBottom() + 4;
        String owned = "所持チャンク: " + Numbers.format(view.getInt("owned"));
        int max = view.getInt("max");
        if (max > 0) owned += " / " + Numbers.format(max);
        String price = "次の価格: " + Numbers.format(view.getLong("price")) + " GC";
        String coins = "所持コイン: " + Numbers.format(view.getLong("coins"));
        graphics.drawString(font, fit(owned, layout.gridWidth() / 2), layout.left(), y, 0xFFFFFF);
        graphics.drawString(font, fit(price, layout.gridWidth() / 2), layout.left() + layout.gridWidth() / 2, y, 0xFFFFFF);
        graphics.drawString(font, fit(coins, layout.gridWidth() / 2), layout.left(), y + 10, 0xFFDDDDCC);
        String legend = "青: 自分 / 赤: 他人 / 緑: 未購入" + (view.getBoolean("protection") ? "" : "（保護は無効）");
        graphics.drawString(font, fit(legend, layout.gridWidth() / 2), layout.left() + layout.gridWidth() / 2, y + 10, 0xA0A0A0);
    }

    private static String readableDimension(String value) {
        int colon = value.indexOf(':');
        return colon >= 0 ? value.substring(colon + 1) : value;
    }

    private String fit(String value, int maxWidth) {
        if (font.width(value) <= maxWidth) return value;
        return font.plainSubstrByWidth(value, Math.max(0, maxWidth - font.width("…"))) + "…";
    }

    @Override public boolean mouseClicked(double x, double y, int button) {
        if (super.mouseClicked(x, y, button)) return true;
        if (layout == null || button != 0) return false;
        int index = layout.indexAt(x, y);
        if (index < 0) return false;
        selected = index;
        updateBuyButton();
        return true;
    }

    @Override public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        return false;
    }

    @Override public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (key == GLFW.GLFW_KEY_ESCAPE) { onClose(); return true; }
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            if (buyButton != null && buyButton.active) { buySelected(); return true; }
        }
        return super.keyPressed(key, scanCode, modifiers);
    }

    @Override public void onClose() {
        if (minecraft.player != null) minecraft.player.closeContainer();
        else minecraft.setScreen(null);
    }
}
