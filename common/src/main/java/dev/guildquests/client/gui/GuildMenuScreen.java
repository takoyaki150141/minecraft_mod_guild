package dev.guildquests.client.gui;

import dev.guildquests.gui.GuildClientMenu;
import dev.guildquests.gui.GuildItems;
import dev.guildquests.gui.GuildMenuKind;
import dev.guildquests.util.Numbers;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.MenuAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import org.lwjgl.glfw.GLFW;
import java.util.ArrayList;
import java.util.List;

/**
 * Row-list presentation of the current server menu, preserving the original navigation.
 * No inventory slots, background texture, chart panels or client-side economic mutations.
 */
public class GuildMenuScreen extends Screen implements MenuAccess<GuildClientMenu> {
    private static final int SCROLL_STEP = 2;
    private static final int SECONDARY_WIDTH = 46;
    private static final String HINT = "▶";
    private final GuildClientMenu menu;
    private final List<Integer> visible = new ArrayList<>();
    private final List<Integer> visibleSnapshot = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();
    private final int[] scroll;
    private int section;
    private GuildGuiLayout layout;
    private Button amountButton;
    private Button navPrev;
    private Button navNext;
    private CompoundTag amount;
    private String headerText;
    private final GuildKeypad keypad = new GuildKeypad();
    private long lastServerAmount = -1;
    private int discardSlot = -1;
    private long discardUntil;
    private boolean loaded;
    private record Row(int slot, int x, int y, int width, int height, String name, List<String> lore,
                       boolean action, boolean secondary) { }

    public GuildMenuScreen(GuildClientMenu menu, Inventory inventory, Component title) {
        super(title);
        this.menu = menu;
        scroll = new int[menu.kind.sections.size()];
        keypad.setApplier(value -> GuildGuiActions.amountSender.accept(menu.containerId, value));
    }

    public static GuildMenuScreen create(GuildClientMenu menu, Inventory inventory, Component title) {
        return switch (menu.kind) {
            case BLACKJACK, CHINCHIRORIN, SLOT, ROULETTE, COIN_FLIP, HORSE_RACE ->
                    new GuildGamblingScreen(menu, inventory, title);
            default -> new GuildMenuScreen(menu, inventory, title);
        };
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override public GuildClientMenu getMenu() { return menu; }

    @Override public void onClose() {
        if (minecraft.player != null) minecraft.player.closeContainer();
        else minecraft.setScreen(null);
    }

    @Override protected void init() { rebuild(); }

    @Override public void tick() {
        if (minecraft.player == null || !minecraft.player.isAlive() || minecraft.player.isRemoved()) { onClose(); return; }
        boolean amountChanged = syncAmount();
        refreshVisible();
        if (amountChanged || !visible.equals(visibleSnapshot)) {
            rebuild();
        } else {
            clampScroll();
            buildRows();
            updateNavigation();
        }
        if (discardSlot >= 0 && System.currentTimeMillis() > discardUntil) discardSlot = -1;
        loaded = true;
        keypad.tick();
    }

    private boolean syncAmount() {
        CompoundTag found = findAmount();
        boolean changed = (found != null) != (amount != null);
        if (found == null) {
            if (keypad.isOpen()) keypad.close();
            amount = null;
            lastServerAmount = -1;
            keypad.setMax(0);
            keypad.reset(0);
            return changed;
        }
        amount = found;
        long value = found.getLong("value");
        keypad.setMax(found.getLong("max"));
        keypad.setWallet(walletOf(found));
        if (!keypad.isOpen() && value != lastServerAmount) keypad.reset(value);
        lastServerAmount = value;
        return changed;
    }

    private CompoundTag findAmount() {
        for (int i = 0; i < menu.kind.rows * 9; i++) {
            var tag = menu.getSlot(i).getItem().getTag();
            if (tag != null && tag.contains("GuildGuiAmount")) return tag.getCompound("GuildGuiAmount");
        }
        return null;
    }

    /** Spendable amount from the payload; older payloads omit it, so fall back to max. */
    private static long walletOf(CompoundTag amount) {
        return amount.contains("wallet", net.minecraft.nbt.Tag.TAG_LONG)
                ? amount.getLong("wallet") : amount.getLong("max");
    }

    private void rebuild() {
        keypad.init(font);
        boolean keypadWasOpen = keypad.isOpen();
        amount = findAmount();
        if (amount != null) {
            long value = amount.getLong("value");
            keypad.setMax(amount.getLong("max"));
            keypad.setWallet(walletOf(amount));
            if (!keypadWasOpen && value != lastServerAmount) keypad.reset(value);
            lastServerAmount = value;
        } else {
            if (keypadWasOpen) keypad.close();
            keypad.setMax(0);
            keypad.reset(0);
            lastServerAmount = -1;
        }
        clearWidgets();
        amountButton = null;
        navPrev = null;
        navNext = null;
        layout = GuildGuiLayout.of(width, height, amount != null);
        int tabCount = menu.kind.sections.size();
        int tabWidth = (layout.width() - GuildGuiLayout.GAP * (tabCount - 1)) / tabCount;
        for (int i = 0; i < tabCount; i++) {
            int selected = i;
            Button tab = button(menu.kind.sections.get(i), layout.left() + i * (tabWidth + GuildGuiLayout.GAP),
                    layout.tabsY(), tabWidth, () -> { section = selected; discardSlot = -1; rebuild(); });
            tab.active = i != section;
        }
        if (amount != null) {
            amountButton = button("入力", layout.left() + layout.width() - 72, layout.amountY(), 72, this::openKeypad);
            amountButton.setTooltip(Tooltip.create(Component.literal("テンキー・キーボード・貼り付けで 0 ～ " + Numbers.format(amount.getLong("max")) + " の整数を入力")));
        }

        int edgeButton = Math.min(76, layout.width() / 4);
        button("閉じる", layout.left() + layout.width() - edgeButton, layout.footerY(), edgeButton, this::onClose);
        if (menu.kind != GuildMenuKind.GUILD) button("戻る", layout.left(), layout.footerY(), edgeButton,
                () -> minecraft.gameMode.handleInventoryButtonClick(menu.containerId, 0));
        int center = layout.left() + layout.width() / 2;
        navPrev = button("<", center - 64, layout.footerY(), 24, () -> turnPage(-1));
        navNext = button(">", center + 40, layout.footerY(), 24, () -> turnPage(1));

        refreshVisible();
        clampScroll();
        buildRows();
        visibleSnapshot.clear();
        visibleSnapshot.addAll(visible);
        updateNavigation();
    }

    private void refreshVisible() {
        visible.clear();
        headerText = null;
        for (int i = 0; i < menu.kind.rows * 9; i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (stack.isEmpty()) continue;
            CompoundTag tag = stack.getTag();
            if (tag != null && tag.contains(GuildItems.HEADER_TAG, net.minecraft.nbt.Tag.TAG_STRING)) {
                if (menu.kind.section(i) == section) headerText = tag.getString(GuildItems.HEADER_TAG);
                continue;
            }
            if (menu.kind.digitSlot(i) || menu.kind.section(i) != section) continue;
            String name = stack.getHoverName().getString();
            if (name.isBlank() || name.equals("閉じる") || stack.is(Items.WHITE_BANNER) && name.equals("Goal")) continue;
            visible.add(i);
        }
        visible.sort(java.util.Comparator.comparingInt(this::priority));
    }

    private int priority(int slot) {
        // Put the transaction beside its amount input, before optional adjustment/help cards.
        return switch (menu.kind) {
            case BANK -> slot == 25 ? -3 : slot == 26 ? -2 : slot;
            case TRANSFER -> slot == 26 ? -2 : slot;
            case AUCTION_SELL -> slot == 18 ? -2 : slot;
            default -> slot;
        };
    }

    private void clampScroll() {
        if (layout == null) return;
        int max = layout.maxScroll(visible.size());
        if (scroll[section] > max) scroll[section] = max;
        if (scroll[section] < 0) scroll[section] = 0;
    }

    private void buildRows() {
        rows.clear();
        if (layout == null) return;
        int first = scroll[section];
        for (int i = first; i < visible.size() && rows.size() < layout.capacity(); i++) {
            int slot = visible.get(i);
            ItemStack stack = menu.getSlot(slot).getItem();
            List<String> lore = new ArrayList<>();
            CompoundTag display = stack.getTagElement("display");
            if (display != null) {
                var lines = display.getList("Lore", net.minecraft.nbt.Tag.TAG_STRING);
                for (int line = 0; line < lines.size(); line++) {
                    try {
                        Component text = Component.Serializer.fromJson(lines.getString(line));
                        if (text != null) lore.add(present(text.getString()));
                    } catch (com.google.gson.JsonParseException ignored) { }
                }
            }
            String name = present(stack.getHoverName().getString());
            boolean action = stack.hasTag() && stack.getTag().getBoolean("GuildGuiAction");
            boolean secondary = action && ((menu.kind == GuildMenuKind.GUILD && slot >= 36 && slot <= 44)
                    || name.startsWith("賭け ±")
                    || lore.stream().anyMatch(line -> line.contains("右クリック")));
            int index = rows.size();
            rows.add(new Row(slot, layout.x(index), layout.y(index), layout.cardWidth(), layout.cardHeight(),
                    name, lore, action, secondary));
        }
    }

    private int contentRight() {
        return layout.left() + layout.width() - (hasScrollbar() ? 8 : 4);
    }

    private boolean hasScrollbar() {
        return layout != null && visible.size() > layout.capacity();
    }

    private String secondaryLabel(Row row) {
        if (menu.kind != GuildMenuKind.GUILD) return "減らす";
        return discardSlot == row.slot() ? "破棄を確定" : "破棄";
    }

    private int secondaryWidth(Row row) {
        return Math.max(SECONDARY_WIDTH, font.width(secondaryLabel(row)) + 8);
    }

    private int secondaryLeft(Row row) {
        return contentRight() - secondaryWidth(row);
    }

    private void secondaryAction(Row row) {
        if (menu.kind == GuildMenuKind.GUILD && discardSlot != row.slot()) {
            discardSlot = row.slot();
            discardUntil = System.currentTimeMillis() + 5000;
            return;
        }
        discardSlot = -1;
        click(row.slot(), 1);
    }

    private Button button(String label, int x, int y, int w, Runnable action) {
        Button result = Button.builder(Component.literal(fit(label, Math.max(1, w - 10))), b -> action.run())
                .bounds(x, y, Math.max(1, w), 20).build();
        if (font.width(label) > w - 10) result.setTooltip(Tooltip.create(Component.literal(label)));
        return addRenderableWidget(result);
    }

    private void click(int slot, int button) {
        if (minecraft.player != null && minecraft.gameMode != null)
            minecraft.gameMode.handleInventoryMouseClick(menu.containerId, slot, button, ClickType.PICKUP, minecraft.player);
    }

    private void openKeypad() {
        if (amount == null) return;
        keypad.setMax(amount.getLong("max"));
        keypad.setWallet(walletOf(amount));
        keypad.open();
        setFocused(null);
    }

    private void closeKeypad() {
        keypad.close();
        keypad.reset(lastServerAmount >= 0 ? lastServerAmount : 0);
    }

    private void applyKeypad() {
        if (amount == null) { keypad.close(); return; }
        keypad.applyOrError();
    }
    private void turnPage(int delta) {
        if (layout == null) return;
        int next = Math.max(0, Math.min(layout.maxScroll(visible.size()),
                scroll[section] + delta * layout.capacity()));
        if (next == scroll[section]) return;
        scroll[section] = next;
        buildRows();
        updateNavigation();
    }

    private void updateNavigation() {
        if (layout == null) return;
        int max = layout.maxScroll(visible.size());
        if (navPrev != null) navPrev.active = scroll[section] > 0;
        if (navNext != null) navNext.active = scroll[section] < max;
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Exactly the in-world dimming used by vanilla menus; never cache or replace the world frame.
        renderBackground(graphics);
        graphics.drawString(font, fit(title.getString(), layout.width()), layout.left(), layout.titleY(), 0xFFFFFF);
        if (height >= 220) {
            String subtitle = headerText != null ? headerText
                    : "冒険者ギルド  /  " + menu.kind.sections.get(section);
            graphics.drawString(font, fit(subtitle, layout.width()), layout.left(), layout.titleY() + 15, 0xC0C0C0);
        }
        if (amount != null) {
            int textWidth = layout.width() - (amountButton == null ? 0 : 76);
            graphics.drawString(font, fit("金額: " + keypad.displayDraft(), textWidth),
                    layout.left(), layout.amountY() + 6, 0xFFFFFF);
        }
        boolean keypadOpen = keypad.isOpen();
        int hovered = -1;
        int hoveredSecondary = -1;
        int hintWidth = font.width(HINT);
        boolean overScrollbar = !keypadOpen && hasScrollbar()
                && mouseX >= layout.left() + layout.width() - 4 && mouseX < layout.left() + layout.width()
                && mouseY >= layout.top() && mouseY < layout.footerY();
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            boolean hover = !keypadOpen && !overScrollbar && mouseX >= row.x() && mouseX < row.x() + row.width()
                    && mouseY >= row.y() && mouseY < row.y() + row.height();
            if (hover) {
                hovered = i;
                graphics.fill(row.x(), row.y(), row.x() + row.width(), row.y() + row.height(), 0x40FFFFFF);
            }
            graphics.renderItem(menu.getSlot(row.slot()).getItem(), row.x() + 4, row.y() + 1);
            int nameX = row.x() + 24;
            int nameRight = !row.action() ? contentRight()
                    : row.secondary() ? secondaryLeft(row) - hintWidth - 8 : contentRight() - hintWidth - 4;
            int nameWidth = Math.max(0, nameRight - nameX);
            if (nameWidth > 0)
                graphics.drawString(font, fit(row.name(), nameWidth), nameX, row.y() + 5, 0xFFFFFF);
            if (row.action()) {
                int hintX = row.secondary() ? secondaryLeft(row) - 4 - hintWidth : contentRight() - hintWidth;
                graphics.drawString(font, HINT, hintX, row.y() + 5, 0xFFAAAAAA);
            }
            if (row.secondary()) {
                int bx = secondaryLeft(row);
                int bw = secondaryWidth(row);
                int by = row.y() + 1;
                int bh = row.height() - 2;
                boolean buttonHover = !keypadOpen && mouseX >= bx && mouseX < bx + bw && mouseY >= by && mouseY < by + bh;
                if (buttonHover) hoveredSecondary = i;
                int fill = discardSlot == row.slot() ? 0xFF8C3B3B : buttonHover ? 0xFF7A7A7A : 0xFF4F4F4F;
                graphics.fill(bx, by, bx + bw, by + bh, fill);
                graphics.fill(bx, by, bx + bw, by + 1, 0xB0FFFFFF);
                graphics.drawCenteredString(font, fit(secondaryLabel(row), bw - 4), bx + bw / 2, by + (bh - 8) / 2, 0xFFFFFF);
            }
            if (menu.kind == GuildMenuKind.HORSE_RACE && row.slot() < 36 && !row.action())
                renderRaceProgress(graphics, row);
        }
        if (hasScrollbar()) {
            int trackX = layout.left() + layout.width() - 4;
            int trackY = layout.top();
            int trackHeight = layout.capacity() * layout.cardHeight();
            graphics.fill(trackX, trackY, trackX + 4, trackY + trackHeight, 0x40000000);
            int thumbHeight = Math.max(10, trackHeight * layout.capacity() / visible.size());
            int max = Math.max(1, layout.maxScroll(visible.size()));
            int thumbY = trackY + (trackHeight - thumbHeight) * scroll[section] / max;
            graphics.fill(trackX, thumbY, trackX + 4, thumbY + thumbHeight, 0xFFB8B8B8);
        }
        for (var child : children()) if (child instanceof net.minecraft.client.gui.components.Renderable widget)
            widget.render(graphics, mouseX, mouseY, partialTick);
        int total = visible.size();
        String page = total == 0 ? "0 / 0"
                : Numbers.format(scroll[section] + 1) + "-" + Numbers.format(Math.min(scroll[section] + layout.capacity(), total)) + " / " + Numbers.format(total);
        graphics.drawCenteredString(font, page, layout.left() + layout.width() / 2, layout.footerY() + 6, 0xFFFFFF);
        if (visible.isEmpty()) graphics.drawCenteredString(font, loaded ? "表示する項目はありません" : "読み込み中…",
                width / 2, layout.top() + 16, 0xCCCCCC);
        if (keypadOpen) keypad.render(graphics, font, keypadBounds(), width, height, mouseX, mouseY);
        if (!keypadOpen && hovered >= 0 && hoveredSecondary < 0) {
            List<FormattedCharSequence> tooltip = new ArrayList<>();
            int wrapWidth = Math.max(100, Math.min(360, width - 30));
            for (Component line : menu.getSlot(rows.get(hovered).slot()).getItem().getTooltipLines(minecraft.player, TooltipFlag.Default.NORMAL))
                tooltip.addAll(font.split(Component.literal(present(line.getString())), wrapWidth));
            graphics.renderTooltip(font, tooltip, mouseX, mouseY);
        }
    }

    private void renderRaceProgress(GuiGraphics graphics, Row row) {
        for (String value : row.lore()) {
            if (!value.matches("[0-9]+%")) continue;
            int progress = Math.min(100, Integer.parseInt(value.substring(0, value.length() - 1)));
            int left = row.x() + 24;
            int right = row.secondary() ? secondaryLeft(row) - 6 : contentRight();
            if (right <= left) return;
            int y = row.y() + row.height() - 4;
            graphics.fill(left, y, right, y + 3, 0xFF444444);
            graphics.fill(left, y, left + (right - left) * progress / 100, y + 3, 0xFF8FB467);
        }
    }

    private String fit(String value, int width) {
        if (font.width(value) <= width) return value;
        return font.plainSubstrByWidth(value, Math.max(0, width - font.width("…"))) + "…";
    }
    private static String present(String value) {
        return value.replace("数字頭・紙", "金額入力欄・加算ボタン").replace("数字頭または紙", "金額入力欄")
                .replace("紙ボタンまたは数字頭", "金額入力欄").replace("右の紙・数字頭", "金額入力欄")
                .replace("左のプレイヤー頭", "送金先の一覧").replace("左の頭を選択", "送金先を選択")
                .replace("4段目に現在の受注クエストを表示します。", "受注中のクエストを表示します。")
                .replace("下段の", "操作の").replace("下の一覧", "一覧");
    }

    private GuildKeypad.Bounds keypadBounds() {
        return new GuildKeypad.Bounds(layout.left(), layout.top(),
                layout.left() + layout.width(), layout.footerY());
    }

    @Override public boolean mouseClicked(double x, double y, int button) {
        if (keypad.isOpen() && layout != null) {
            switch (keypad.click(x, y, button, keypadBounds(), width, height)) {
                case APPLY -> applyKeypad();
                case CLOSE -> closeKeypad();
                default -> { }
            }
            setFocused(null);
            return true;
        }
        for (GuiEventListener child : children()) if (child.mouseClicked(x, y, button)) {
            setFocused(child); if (button == 0) setDragging(true); return true;
        }
        setFocused(null);
        if (layout == null) return false;
        if (hasScrollbar() && x >= layout.left() + layout.width() - 4
                && y >= layout.top() && y < layout.footerY()) return true;
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            if (x < row.x() || x >= row.x() + row.width() || y < row.y() || y >= row.y() + row.height()) continue;
            if (button == 0) {
                if (row.secondary()) {
                    int bx = secondaryLeft(row);
                    if (x >= bx && x < bx + secondaryWidth(row)) { secondaryAction(row); return true; }
                }
                if (row.action()) click(row.slot(), 0);
                return true;
            }
            if (button == 1 && row.secondary()) { secondaryAction(row); return true; }
            return false;
        }
        return false;
    }
    @Override public boolean mouseReleased(double x, double y, int button) {
        setDragging(false);
        if (keypad.isOpen()) return true;
        return getFocused() != null && getFocused().mouseReleased(x, y, button);
    }
    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (keypad.isOpen()) { keypad.mouseDragged(x, y, button, dx, dy); return true; }
        return getFocused() != null && getFocused().mouseDragged(x, y, button, dx, dy);
    }
    @Override public boolean mouseScrolled(double x, double y, double delta) {
        if (keypad.isOpen()) return true;
        if (layout == null || delta == 0 || !hasScrollbar()) return false;
        if (x < layout.left() || x >= layout.left() + layout.width()
                || y < layout.top() || y >= layout.footerY()) return false;
        int max = layout.maxScroll(visible.size());
        int next = Math.max(0, Math.min(max, scroll[section] - (int) Math.round(delta * SCROLL_STEP)));
        if (next == scroll[section]) return false;
        scroll[section] = next;
        buildRows();
        updateNavigation();
        return true;
    }
    @Override public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (keypad.isOpen()) {
            if (key == GLFW.GLFW_KEY_ESCAPE) { closeKeypad(); return true; }
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) { applyKeypad(); return true; }
            if (key == GLFW.GLFW_KEY_TAB) { keypad.refocus(); return true; }
            keypad.keyPressed(key, scanCode, modifiers);
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) { onClose(); return true; }
        if (key == GLFW.GLFW_KEY_PAGE_DOWN || key == GLFW.GLFW_KEY_PAGE_UP) { turnPage(key == GLFW.GLFW_KEY_PAGE_DOWN ? 1 : -1); return true; }
        return super.keyPressed(key, scanCode, modifiers);
    }
    @Override public boolean charTyped(char character, int modifiers) {
        if (keypad.isOpen()) { keypad.charTyped(character, modifiers); return true; }
        return super.charTyped(character, modifiers);
    }
}
