package dev.guildquests.client.gui;

import dev.guildquests.gui.GuildClientMenu;
import dev.guildquests.gui.GuildItems;
import dev.guildquests.gui.LandView;
import dev.guildquests.neoforge.NeoForgeNetwork;
import dev.guildquests.util.Numbers;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.MenuAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/** Land offered for sale, presented as an auction-style list. */
public final class LandMarketScreen extends Screen implements MenuAccess<GuildClientMenu> {
    private final GuildClientMenu menu;
    private final Screen parent;
    private final List<Row> rows = new ArrayList<>();
    private int selected = -1;
    private int page;
    private int pageSize = 6;
    private int rowLeft;
    private int rowTop;
    private int rowWidth;
    private int rowHeight = 18;
    private Button buyButton;

    private record Row(int x, int z, long price, String seller, boolean mine) { }

    public LandMarketScreen(GuildClientMenu menu, Screen parent, Component title) {
        super(title);
        this.menu = menu;
        this.parent = parent;
    }

    @Override public GuildClientMenu getMenu() { return menu; }

    @Override public boolean isPauseScreen() { return false; }

    @Override protected void init() {
        readRows();
        layout();
    }

    @Override public void tick() {
        if (minecraft.player == null || !minecraft.player.isAlive() || minecraft.player.isRemoved()) {
            back();
            return;
        }
        int before = rows.size();
        readRows();
        if (before != rows.size()) {
            clampPage();
            updateButtons();
        }    }

    private void readRows() {
        CompoundTag tag = GuildItems.tag(menu.getSlot(0).getItem());
        CompoundTag view = tag.contains(LandView.TAG) ? tag.getCompound(LandView.TAG) : new CompoundTag();
        ListTag list = view.getList("market", Tag.TAG_COMPOUND);
        rows.clear();
        for (int i = 0; i < list.size(); i++) {
            CompoundTag row = list.getCompound(i);
            rows.add(new Row(row.getInt("x"), row.getInt("z"), row.getLong("price"),
                    row.getString("seller"), row.getBoolean("self")));
        }
    }

    private void layout() {
        clearWidgets();
        int margin = Math.max(8, Math.min(24, width / 40));
        rowLeft = margin;
        rowWidth = Math.max(160, width - margin * 2);
        rowTop = 46;
        rowHeight = Math.max(14, Math.min(20, (height - rowTop - 56) / Math.max(1, Math.min(12, Math.max(6, rows.size())))));
        pageSize = Math.max(3, (height - rowTop - 58) / rowHeight);
        clampPage();

        int y = Math.min(height - 26, rowTop + pageSize * rowHeight + 6);
        int gap = 6;
        int sideWidth = Math.max(48, Math.min(80, rowWidth / 5));
        int buyWidth = Math.max(96, rowWidth - sideWidth * 3 - gap * 3);
        int x = rowLeft;
        addRenderableWidget(Button.builder(Component.literal("戻る"), b -> back())
                .bounds(x, y, sideWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal("<"), b -> turnPage(-1))
                .bounds(x + sideWidth + gap, y, sideWidth, 20).build());
        buyButton = addRenderableWidget(Button.builder(Component.literal("購入"), b -> buySelected())
                .bounds(x + (sideWidth + gap) * 2, y, buyWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal(">"), b -> turnPage(1))
                .bounds(x + (sideWidth + gap) * 2 + buyWidth + gap, y, sideWidth, 20).build());
        updateButtons();
    }

    private void clampPage() {
        int pages = Math.max(1, (rows.size() + pageSize - 1) / pageSize);
        page = Math.max(0, Math.min(pages - 1, page));
        if (selected >= rows.size()) selected = -1;
    }

    private void turnPage(int delta) {
        page += delta;
        clampPage();
        selected = -1;
        updateButtons();
    }

    private void updateButtons() {
        if (buyButton == null) return;
        boolean valid = selected >= 0 && selected < rows.size() && !rows.get(selected).mine();
        buyButton.active = valid;
        buyButton.setMessage(Component.literal(valid
                ? "購入 (" + Numbers.format(rows.get(selected).price()) + " GC)"
                : "購入"));
    }

    private void buySelected() {
        if (selected < 0 || selected >= rows.size()) return;
        Row row = rows.get(selected);
        if (row.mine()) return;
        NeoForgeNetwork.sendLandSale(3, row.x(), row.z(), 0);
        selected = -1;
        updateButtons();
    }

    private void back() {
        minecraft.setScreen(parent);
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderTransparentBackground(graphics);
        int margin = Math.max(8, Math.min(24, width / 40));
        graphics.drawString(font, "土地の出品一覧", margin, 10, 0xFFFFFF);
        graphics.drawString(font, fit("出品されたチャンクを価格の安い順に表示します", width - margin * 2),
                margin, 22, 0xA0A0A0);

        int pages = Math.max(1, (rows.size() + pageSize - 1) / pageSize);
        graphics.drawString(font, (page + 1) + " / " + pages + " ページ",
                margin, 33, 0x808080);
        graphics.drawString(font, "座標", rowLeft + 4, rowTop - 10, 0xA0A0A0);
        graphics.drawString(font, "出品者", rowLeft + rowWidth / 2 - 20, rowTop - 10, 0xA0A0A0);
        String priceHeader = "価格";
        graphics.drawString(font, priceHeader, rowLeft + rowWidth - font.width(priceHeader) - 4,
                rowTop - 10, 0xA0A0A0);

        if (rows.isEmpty()) {
            graphics.drawCenteredString(font, "出品はありません", width / 2, rowTop + 20, 0xCCCCCC);
        } else {
            int start = page * pageSize;
            int end = Math.min(rows.size(), start + pageSize);
            for (int index = start; index < end; index++) {
                Row row = rows.get(index);
                int y = rowTop + (index - start) * rowHeight;
                boolean hovered = mouseX >= rowLeft && mouseX < rowLeft + rowWidth
                        && mouseY >= y && mouseY < y + rowHeight - 2;
                int background = index == selected ? 0x60FFD700 : hovered ? 0x30FFFFFF : 0x30000000;
                graphics.fill(rowLeft, y, rowLeft + rowWidth, y + rowHeight - 2, background);
                String coords = "(" + row.x() + ", " + row.z() + ")" + (row.mine() ? " 自分" : "");
                String price = Numbers.format(row.price()) + " GC";
                graphics.drawString(font, coords, rowLeft + 4, y + 4, 0xFFFFFF);
                graphics.drawString(font, fit(row.seller(), rowWidth / 2 - 30),
                        rowLeft + rowWidth / 2 - 20, y + 4, row.mine() ? 0xAAAAAA : 0xC0E0FF);
                graphics.drawString(font, price, rowLeft + rowWidth - font.width(price) - 4, y + 4, 0xFFE8B0);
            }
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private String fit(String value, int maxWidth) {
        if (font.width(value) <= maxWidth) return value;
        return font.plainSubstrByWidth(value, Math.max(0, maxWidth - font.width("…"))) + "…";
    }

    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        if (button != 0) return false;
        if (mouseX < rowLeft || mouseX >= rowLeft + rowWidth) return false;
        int start = page * pageSize;
        int end = Math.min(rows.size(), start + pageSize);
        for (int index = start; index < end; index++) {
            int y = rowTop + (index - start) * rowHeight;
            if (mouseY >= y && mouseY < y + rowHeight - 2) {
                selected = index;
                updateButtons();
                return true;
            }
        }
        return false;
    }

    @Override public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            back();
            return true;
        }
        if (key == GLFW.GLFW_KEY_PAGE_UP) {
            turnPage(-1);
            return true;
        }
        if (key == GLFW.GLFW_KEY_PAGE_DOWN) {
            turnPage(1);
            return true;
        }
        return super.keyPressed(key, scanCode, modifiers);
    }
}
