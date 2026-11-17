package dev.guildquests.client.gui;

/** All coordinates derive from the current scaled viewport, including GUI-scale changes. */
public record GuildGuiLayout(int left, int width, int titleY, int tabsY, int amountY,
                             int top, int footerY, int columns, int rows, int cardWidth, int cardHeight) {
    public static final int GAP = 6;
    public static final int ROW_HEIGHT = 18;

    public static GuildGuiLayout of(int width, int height, boolean amount) {
        int margin = Math.max(8, Math.min(24, width / 35));
        int bodyWidth = Math.max(1, Math.min(920, width - margin * 2));
        boolean compact = height < 220;
        int titleY = compact ? 6 : 12;
        int tabsY = compact ? 24 : 42;
        int amountY = tabsY + 26;
        int top = amount ? amountY + 31 : tabsY + 27;
        int footerY = height - 28;
        int rows = Math.max(1, (footerY - top) / ROW_HEIGHT);
        return new GuildGuiLayout((width - bodyWidth) / 2, bodyWidth, titleY, tabsY, amountY,
                top, footerY, 1, rows, bodyWidth, ROW_HEIGHT);
    }

    public int capacity() { return columns * rows; }
    public int x(int index) { return left; }
    public int y(int index) { return top + index * cardHeight; }
    public int pageCount(int count) { return Math.max(1, (count + capacity() - 1) / capacity()); }
    public int maxScroll(int count) { return Math.max(0, count - capacity()); }
}
