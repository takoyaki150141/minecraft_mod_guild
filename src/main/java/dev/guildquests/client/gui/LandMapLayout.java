package dev.guildquests.client.gui;

/** Geometry of the chunk map, independent of rendering so it can be unit-tested. */
public record LandMapLayout(int left, int top, int cell, int radius, int side) {
    public static LandMapLayout of(int width, int height, int radius) {
        int clamped = Math.max(1, Math.min(8, radius));
        int side = clamped * 2 + 1;
        int available = Math.max(64, Math.min(width - 48, height - 150));
        int cell = Math.max(8, Math.min(22, available / side));
        int grid = cell * side;
        int left = (width - grid) / 2;
        int top = Math.max(54, (height - grid) / 2 - 4);
        return new LandMapLayout(left, top, cell, clamped, side);
    }

    public int gridWidth() { return cell * side; }

    public int gridBottom() { return top + cell * side; }

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
