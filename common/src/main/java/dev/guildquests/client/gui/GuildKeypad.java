package dev.guildquests.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import dev.guildquests.util.Numbers;
import org.lwjgl.glfw.GLFW;

final class GuildKeypad {
    enum Result { NONE, HANDLED, APPLY, CLOSE }

    private static final String[] KEYS = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "00", "0", "←"};
    private static final int COLUMNS = 3;
    private static final int MAX_DIGITS = 19;
    private static final int CLOSE_WIDTH = 52;

    private boolean open;
    private String draft = "0";
    private long max = Long.MAX_VALUE;
    private EditBox input;
    private long wallet = Long.MAX_VALUE;

    record Bounds(int left, int top, int right, int bottom) {
        int width() { return right - left; }
        int height() { return bottom - top; }
    }

    private record Rect(int x, int y, int w, int h) {
        boolean contains(double px, double py) { return px >= x && px < x + w && py >= y && py < y + h; }
    }

    private record Metrics(int panelX, int panelY, int panelW, int panelH,
                           int keyX, int keyY, int keyW, int keyH,
                           int headerY, int headerH, int clearY, int bottomY,
                           int pad, int gap) { }

    boolean isOpen() { return open; }
    void init(Font font) {
        if (input != null) return;
        input = new EditBox(font, 0, 0, 100, 20, Component.literal("金額"));
        input.setMaxLength(MAX_DIGITS);
        input.setFilter(this::validDraft);
        input.setValue(draft);
        input.setResponder(value -> draft = value);
        input.setCanLoseFocus(false);
    }
    void open() {
        open = true;
        input.setFocused(true);
        input.moveCursorToEnd();
        input.setHighlightPos(0);
    }
    void close() {
        open = false;
        if (input != null) input.setFocused(false);
    }
    String draft() { return draft; }

    /** Grouped form for read-only labels; the EditBox itself keeps plain digits. */
    String displayDraft() {
        try {
            return Numbers.format(Long.parseLong(draft));
        } catch (NumberFormatException ex) {
            return draft;
        }
    }

    void setMax(long max) {
        this.max = Math.max(0, max);
        clampDraft();
    }

    void setWallet(long wallet) {
        this.wallet = wallet;
    }

    void reset(long value) {
        setDraft(Long.toString(Math.max(0, Math.min(max, value))));
    }

    Long value() {
        try {
            long parsed = Long.parseLong(draft);
            return parsed >= 0 && parsed <= max ? parsed : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private boolean validDraft(String text) {
        if (text.isEmpty()) return true;
        if (text.length() > MAX_DIGITS || !text.chars().allMatch(c -> c >= '0' && c <= '9')) return false;
        try {
            return Long.parseLong(text) <= max;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    private void setDraft(String text) {
        draft = text;
        if (input != null) input.setValue(text);
    }

    boolean keyPressed(int key, int scanCode, int modifiers) {
        return input.keyPressed(key, scanCode, modifiers);
    }

    boolean charTyped(char character, int modifiers) {
        return input.charTyped(character, modifiers);
    }

    boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        return input.mouseDragged(x, y, button, dx, dy);
    }

    void tick() { if (input != null && open) input.tick(); }

    private void clear() {
        setDraft("0");
        input.moveCursorToEnd();
        input.setHighlightPos(0);
    }

    /** "全額": fill with the spendable wallet, never above the menu's max. */
    void fillWallet() {
        long target = Math.max(0, Math.min(max, wallet));
        setDraft(Long.toString(target));
    }

    private void clampDraft() {
        if (draft.isEmpty()) return;
        try {
            long parsed = Long.parseLong(draft);
            if (parsed > max) setDraft(Long.toString(max));
        } catch (NumberFormatException ex) {
            setDraft("0");
        }
    }

    void render(GuiGraphics graphics, Font font, Bounds area, int screenWidth, int screenHeight,
                int mouseX, int mouseY) {
        if (!open) return;
        graphics.fill(area.left(), area.top(), area.right(), area.bottom(), 0x80000000);
        Metrics m = metrics(area, screenWidth, screenHeight);
        graphics.fill(m.panelX, m.panelY, m.panelX + m.panelW, m.panelY + m.panelH, 0xF0181818);
        border(graphics, m.panelX, m.panelY, m.panelW, m.panelH);

        positionInput(m);
        input.render(graphics, mouseX, mouseY, 0);

        for (int i = 0; i < KEYS.length; i++) {
            Rect key = keyRect(m, i);
            key(graphics, font, key, KEYS[i], key.contains(mouseX, mouseY), 0xFF3C3C3C, 0xFF585858);
        }
        Rect clear = clearRect(m);
        key(graphics, font, clear, "クリア", clear.contains(mouseX, mouseY), 0xFF4A2C2C, 0xFF6B3D3D);
        Rect wallet = walletRect(m);
        key(graphics, font, wallet, "全額", wallet.contains(mouseX, mouseY), 0xFF22303C, 0xFF33485A);
        Rect confirm = confirmRect(m);
        key(graphics, font, confirm, "確定", confirm.contains(mouseX, mouseY), 0xFF2C4A30, 0xFF3D6B43);
        Rect close = closeRect(m);
        key(graphics, font, close, "閉じる", close.contains(mouseX, mouseY), 0xFF303030, 0xFF4A4A4A);
    }

    Result click(double mouseX, double mouseY, int button, Bounds area, int screenWidth, int screenHeight) {
        if (!open) return Result.NONE;
        if (button != 0) return Result.HANDLED;
        Metrics m = metrics(area, screenWidth, screenHeight);
        positionInput(m);
        if (input.mouseClicked(mouseX, mouseY, button)) return Result.HANDLED;
        if (closeRect(m).contains(mouseX, mouseY)) return Result.CLOSE;
        if (confirmRect(m).contains(mouseX, mouseY)) return value() != null ? Result.APPLY : Result.HANDLED;
        if (clearRect(m).contains(mouseX, mouseY)) { clear(); return Result.HANDLED; }
        if (walletRect(m).contains(mouseX, mouseY)) { fillWallet(); return Result.HANDLED; }
        for (int i = 0; i < KEYS.length; i++) {
            if (!keyRect(m, i).contains(mouseX, mouseY)) continue;
            press(i);
            return Result.HANDLED;
        }
        if (mouseX >= m.panelX && mouseX < m.panelX + m.panelW
                && mouseY >= m.panelY && mouseY < m.panelY + m.panelH) return Result.HANDLED;
        return Result.CLOSE;
    }

    private void press(int index) {
        if (index == 11) input.keyPressed(GLFW.GLFW_KEY_BACKSPACE, 0, 0);
        else input.insertText(KEYS[index]);
    }

    private void positionInput(Metrics m) {
        input.setX(m.panelX + m.pad);
        input.setY(m.headerY);
        input.setWidth(m.panelW - m.pad * 2);
    }

    private Metrics metrics(Bounds area, int screenWidth, int screenHeight) {
        int pad = 8;
        int gap = 4;
        int headerH = 20;
        int panelW = Math.min(168, Math.max(96, area.width() - 8));
        int available = Math.max(96, Math.min(area.height() - 8, screenHeight - 8));
        int fixed = pad * 2 + headerH + gap * 6 + 40;
        int keyH = Math.max(12, Math.min(22, (available - fixed) / 4));
        int keyW = Math.max(20, (panelW - pad * 2 - gap * 2) / 3);
        int panelH = fixed + 4 * keyH;
        int panelX = area.left() + (area.width() - panelW) / 2;
        int panelY = area.top() + (area.height() - panelH) / 2;
        panelX = Math.max(2, Math.min(screenWidth - panelW - 2, panelX));
        panelY = Math.max(2, Math.min(screenHeight - panelH - 2, panelY));
        int headerY = panelY + pad;
        int keyX = panelX + pad;
        int keyY = headerY + headerH + gap;
        int clearY = keyY + 4 * keyH + 3 * gap + gap;
        int bottomY = clearY + 20 + gap;
        return new Metrics(panelX, panelY, panelW, panelH, keyX, keyY, keyW, keyH,
                headerY, headerH, clearY, bottomY, pad, gap);
    }

    private Rect keyRect(Metrics m, int index) {
        return new Rect(m.keyX + (index % COLUMNS) * (m.keyW + m.gap),
                m.keyY + (index / COLUMNS) * (m.keyH + m.gap), m.keyW, m.keyH);
    }

    private Rect clearRect(Metrics m) {
        int content = m.panelW - m.pad * 2;
        int w = (content - m.gap) / 2;
        return new Rect(m.panelX + m.pad, m.clearY, w, 20);
    }

    /** Right half of the clear row: fills the draft with the spendable wallet. */
    private Rect walletRect(Metrics m) {
        int content = m.panelW - m.pad * 2;
        int w = (content - m.gap) / 2;
        return new Rect(m.panelX + m.pad + w + m.gap, m.clearY, content - w - m.gap, 20);
    }

    private Rect confirmRect(Metrics m) {
        return new Rect(m.panelX + m.pad, m.bottomY,
                m.panelW - m.pad * 2 - CLOSE_WIDTH - m.gap, 20);
    }

    private Rect closeRect(Metrics m) {
        return new Rect(m.panelX + m.panelW - m.pad - CLOSE_WIDTH, m.bottomY, CLOSE_WIDTH, 20);
    }

    private void key(GuiGraphics graphics, Font font, Rect rect, String label, boolean hovered,
                     int base, int highlight) {
        graphics.fill(rect.x, rect.y, rect.x + rect.w, rect.y + rect.h, hovered ? highlight : base);
        graphics.fill(rect.x, rect.y, rect.x + rect.w, rect.y + 1, 0x40FFFFFF);
        String text = label;
        int limit = rect.w - 4;
        if (font.width(text) > limit)
            text = font.plainSubstrByWidth(text, Math.max(0, limit - font.width("…"))) + "…";
        graphics.drawCenteredString(font, text, rect.x + rect.w / 2, rect.y + (rect.h - 8) / 2, 0xFFFFFFFF);
    }

    private void border(GuiGraphics graphics, int x, int y, int w, int h) {
        graphics.fill(x, y, x + w, y + 1, 0xFF707070);
        graphics.fill(x, y + h - 1, x + w, y + h, 0xFF303030);
        graphics.fill(x, y, x + 1, y + h, 0xFF707070);
        graphics.fill(x + w - 1, y, x + w, y + h, 0xFF303030);
    }
}
