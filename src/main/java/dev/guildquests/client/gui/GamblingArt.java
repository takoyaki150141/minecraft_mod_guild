package dev.guildquests.client.gui;

import com.mojang.math.Axis;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/** Pixel art and vanilla block textures; no external fonts, web panels or raster assets. */
final class GamblingArt {
    static final int INK = 0xFF32291F, CREAM = 0xFFEAE1C6, GOLD = 0xFFE3BD64;
    private static final ResourceLocation WOOD = ResourceLocation.fromNamespaceAndPath("minecraft", "textures/block/spruce_planks.png");
    private GamblingArt() { }

    static void frame(GuiGraphics g, int x, int y, int w, int h, int felt) {
        g.fill(x + 3, y + 3, x + w + 3, y + h + 3, 0x80000000);
        for (int dx = 0; dx < w; dx += 16) for (int dy = 0; dy < h; dy += 16)
            g.blit(WOOD, x + dx, y + dy, 0, 0, Math.min(16, w - dx), Math.min(16, h - dy), 16, 16);
        outline(g, x, y, w, h, 0xFF231A12);
        g.hLine(x + 1, x + w - 2, y + 1, 0xFFA17B49);
        g.fill(x + 6, y + 6, x + w - 6, y + h - 6, felt);
        outline(g, x + 5, y + 5, w - 10, h - 10, 0xFF251D16);
        for (int dx : new int[]{2, w - 4}) for (int dy : new int[]{2, h - 4})
            g.fill(x + dx, y + dy, x + dx + 2, y + dy + 2, GOLD);
    }

    static void outline(GuiGraphics g, int x, int y, int w, int h, int color) {
        if (w < 1 || h < 1) return;
        g.hLine(x, x + w - 1, y, color); g.hLine(x, x + w - 1, y + h - 1, color);
        g.vLine(x, y, y + h - 1, color); g.vLine(x + w - 1, y, y + h - 1, color);
    }

    static void item(GuiGraphics g, ItemStack stack, float x, float y, float size) {
        g.pose().pushPose(); g.pose().translate(x, y, 0); g.pose().scale(size / 16f, size / 16f, 1);
        g.renderItem(stack, 0, 0); g.pose().popPose();
    }

    static void bitmap(GuiGraphics g, String[] pixels, int x, int y, int scale, int color) {
        for (int row = 0; row < pixels.length; row++) for (int col = 0; col < pixels[row].length(); col++)
            if (pixels[row].charAt(col) != '0') g.fill(x + col * scale, y + row * scale,
                    x + (col + 1) * scale, y + (row + 1) * scale, color);
    }

    static void suit(GuiGraphics g, String suit, int x, int y, int scale) {
        String[] pixels = switch (suit) {
            case "♥" -> new String[]{"01010", "11111", "11111", "01110", "00100"};
            case "♦" -> new String[]{"00100", "01110", "11111", "01110", "00100"};
            case "♣" -> new String[]{"00100", "01110", "10101", "11111", "00100", "01110"};
            default -> new String[]{"00100", "01110", "11111", "11111", "00100", "01110"};
        };
        bitmap(g, pixels, x, y, scale, suit.equals("♥") || suit.equals("♦") ? 0xFFA63232 : 0xFF24272B);
    }

    static void card(GuiGraphics g, Font font, int x, int y, int w, int h, String rank, String suit, boolean hidden) {
        g.fill(x + 2, y + 2, x + w + 2, y + h + 2, 0x70000000);
        g.fill(x, y, x + w, y + h, CREAM);
        outline(g, x, y, w, h, 0xFF6D6655);
        if (hidden) {
            g.fill(x + 3, y + 3, x + w - 3, y + h - 3, 0xFF293F69);
            for (int dx = 4; dx < w - 4; dx += 4) for (int dy = 4; dy < h - 4; dy += 4)
                g.fill(x + dx, y + dy, x + dx + 2, y + dy + 2, 0xFF7187AB);
            int s = Math.max(1, Math.min(3, w / 10));
            bitmap(g, new String[]{"00100", "01110", "11111", "01110", "00100"},
                    x + (w - 5 * s) / 2, y + (h - 5 * s) / 2, s, CREAM);
            return;
        }
        int color = suit.equals("♥") || suit.equals("♦") ? 0xFFA63232 : 0xFF24272B;
        float textScale = h < 38 ? 0.8f : 1;
        g.pose().pushPose(); g.pose().translate(x + 3, y + 3, 0); g.pose().scale(textScale, textScale, 1);
        g.drawString(font, rank, 0, 0, color, false); g.pose().popPose();
        if (h >= 42) {
            g.pose().pushPose(); g.pose().translate(x + w - 3, y + h - 3, 0);
            g.pose().mulPose(Axis.ZP.rotationDegrees(180)); g.drawString(font, rank, 0, 0, color, false); g.pose().popPose();
        }
        int s = Math.max(1, Math.min(4, Math.min(w / 9, h / 16)));
        if (rank.equals("J") || rank.equals("Q") || rank.equals("K")) {
            bitmap(g, new String[]{"10101", "11111", "01110", "01110"}, x + (w - s * 5) / 2,
                    y + h / 2 - s * 3, s, 0xFFBB8F33);
            suit(g, suit, x + (w - 5 * s) / 2, y + h / 2 + 2, s);
        } else if (h >= 62 && w >= 40 && !rank.equals("A")) {
            int n = Integer.parseInt(rank), rows = (n + 1) / 2;
            int pipScale = Math.max(1, Math.min(2, (h - 24) / Math.max(1, rows * 7)));
            for (int i = 0; i < n; i++) {
                int px = n % 2 == 1 && i == n - 1 ? x + w / 2 : x + w / 3 + i % 2 * (w / 3);
                int py = y + 14 + (i / 2) * Math.max(7 * pipScale, (h - 30) / Math.max(1, rows - 1));
                suit(g, suit, px - pipScale * 2, py, pipScale);
            }
        } else suit(g, suit, x + (w - 5 * s) / 2, y + h / 2 - s, s);
    }

    static void die(GuiGraphics g, int x, int y, int size, int value) {
        g.fill(x + 2, y + 3, x + size + 2, y + size + 3, 0x60000000);
        g.fill(x, y, x + size, y + size, CREAM); outline(g, x, y, size, size, 0xFF938B79);
        g.hLine(x + 1, x + size - 2, y + 1, 0xFFFFF7E4);
        int a = size / 4, b = size - a, p = Math.max(2, size / 9), c = size / 2;
        if (value % 2 == 1) pip(g, x + c, y + c, p, value == 1 ? 0xFFAF3434 : INK);
        if (value >= 2) { pip(g, x + a, y + a, p, INK); pip(g, x + b, y + b, p, INK); }
        if (value >= 4) { pip(g, x + b, y + a, p, INK); pip(g, x + a, y + b, p, INK); }
        if (value == 6) { pip(g, x + a, y + c, p, INK); pip(g, x + b, y + c, p, INK); }
    }

    private static void pip(GuiGraphics g, int x, int y, int size, int color) {
        g.fill(x - size / 2, y - size / 2, x + (size + 1) / 2, y + (size + 1) / 2, color);
    }

    static void disk(GuiGraphics g, int cx, int cy, int radius, int color) {
        for (int y = -radius; y <= radius; y += 2) {
            int half = (int)Math.sqrt(Math.max(0, radius * radius - y * y)) / 2 * 2;
            g.fill(cx - half, cy + y, cx + half + 1, cy + y + 2, color);
        }
    }

    static void horse(GuiGraphics g, int x, int y, int scale, int coat, boolean running, double time) {
        bitmap(g, new String[]{"00000000000001010", "00000000000001110", "00000000000011111",
                "00000000000111111", "01111111111111100", "11111111111111000", "11111111111110000",
                "10111111111110000", "10011111111000000"}, x, y, scale, coat);
        g.fill(x + 12 * scale, y + 2 * scale, x + 13 * scale, y + 6 * scale, 0xFF35271E);
        g.fill(x + 15 * scale, y + 2 * scale, x + 16 * scale, y + 3 * scale, 0xFF171512);
        g.fill(x + 5 * scale, y + 4 * scale, x + 9 * scale, y + 6 * scale, 0xFF705132);
        int stride = running ? (int)Math.round(Math.sin(time) * scale) : 0;
        for (int leg : new int[]{3, 5, 10, 12}) {
            int shift = leg % 2 == 0 ? stride : -stride;
            g.fill(x + leg * scale + shift, y + 8 * scale, x + (leg + 1) * scale + shift, y + 12 * scale, coat);
            g.fill(x + leg * scale + shift, y + 11 * scale, x + (leg + 1) * scale + shift, y + 13 * scale, 0xFF2B241D);
        }
    }
}
