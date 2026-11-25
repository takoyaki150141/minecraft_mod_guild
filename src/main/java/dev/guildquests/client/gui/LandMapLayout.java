package dev.guildquests.client.gui;

/**
 * Geometry of the chunk map, independent of rendering so it can be unit-tested.
 * The grid is sized against the space left over after the title and the info lines,
 * so a small window shrinks the cells instead of truncating the text.
 */
public record LandMapLayout(int left, int top, int cell, int radius, int side,
                            int contentLeft, int contentWidth, int infoTop, int infoLines,
                            int lineHeight, int buttonY) {
    public static final int LINE_HEIGHT = 11;
    public static final int BUTTON_HEIGHT = 20;
    public static final int TITLE_HEIGHT = 30;

    public static LandMapLayout of(int width, int height, int radius) {
        return of(width, height, radius, 4);
    }

    public static LandMapLayout of(int width, int height, int radius, int infoLines) {
        int clamped = Math.max(1, Math.min(8, radius));
        int side = clamped * 2 + 1;
        int margin = Math.max(6, Math.min(20, width / 40));
        int contentLeft = margin;
        int contentWidth = Math.max(40, width - margin * 2);

        int lines = Math.max(1, infoLines);
        int buttonY = Math.max(TITLE_HEIGHT + 40, height - BUTTON_HEIGHT - 8);
        int infoTop = Math.max(TITLE_HEIGHT + 20, buttonY - lines * LINE_HEIGHT - 4);
        int availableHeight = Math.max(0, infoTop - TITLE_HEIGHT - 4);
        int availableWidth = Math.max(0, contentWidth - 8);
        int cell = Math.max(4, Math.min(22, Math.min(availableWidth / side, availableHeight / side)));
        int grid = cell * side;
        int left = contentLeft + (contentWidth - grid) / 2;
        int top = TITLE_HEIGHT + Math.max(0, (availableHeight - grid) / 2);
        top = Math.min(top, Math.max(0, infoTop - grid - 2));
        return new LandMapLayout(left, top, cell, clamped, side, contentLeft, contentWidth,
                infoTop, lines, LINE_HEIGHT, buttonY);
    }

    public int gridWidth() { return cell * side; }

    public int gridBottom() { return top + cell * side; }

    public int infoHeight() { return infoLines * lineHeight; }

    public int cellX(int index) { return left + (index % side) * cell; }

    public int cellY(int index) { return top + (index / side) * cell; }

    /** Map index under the given screen coordinates, or -1 when outside the grid. */
    public int indexAt(double x, double y) {
        if (x < left || y < top) return -1;
        int col = (int) ((x - left) / cell);
        int row = (int) ((y - top) / cell);
        if (col < 0 || col >= side || row < 0 || row >= side) return -1;
        return row * side + col;
    }
}
