package dev.guildquests.client.gui;

/** Keeps the play area and the transaction controls separate at every GUI scale. */
public record GamblingLayout(int left, int width, int titleY, int boardY, int boardHeight,
                             int summaryY, int betY, int actionY, int footerY, boolean compact) {
    public static GamblingLayout of(int viewportWidth, int viewportHeight) {
        int margin = Math.max(8, Math.min(16, viewportWidth / 40));
        int width = Math.min(840, viewportWidth - margin * 2);
        int height = Math.min(520, viewportHeight - 12);
        int top = (viewportHeight - height) / 2;
        int footer = top + height - 20;
        int action = footer - 25;
        int summary = action - 14;
        int bet = summary - 24;
        boolean compact = viewportHeight < 270;
        int boardY = top + 26;
        int bottom = (compact ? summary : bet) - 6;
        return new GamblingLayout((viewportWidth - width) / 2, width, top + 2,
                boardY, bottom - boardY, summary, bet, action, footer, compact);
    }

    public int right() { return left + width; }
    public int boardBottom() { return boardY + boardHeight; }
}
