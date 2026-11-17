package dev.guildquests.client.gui;

import com.mojang.math.Axis;
import dev.guildquests.gui.GuildMenuKind;
import net.minecraft.Util;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Arrays;

/** Decorative animation reads public state only; it never draws from the deck or settles bets. */
final class GamblingTableRenderer {
    private static final Item[] SYMBOLS = {Items.DIAMOND, Items.EMERALD, Items.GOLD_INGOT, Items.IRON_INGOT, Items.APPLE, Items.STICK};
    private static final int[] PAYOUTS = {80, 50, 25, 12, 6, 4};
    private static final int[] WHEEL = {0,32,15,19,4,21,2,25,17,34,6,27,13,36,11,30,8,23,10,5,24,16,33,1,20,14,31,9,22,18,29,7,28,12,35,3,26};
    private static final int[] RED = {1,3,5,7,9,12,14,16,18,19,21,23,25,27,30,32,34,36};
    private static final int[] COATS = {0xFFE0DAC8, 0xFF413B39, 0xFFAD6945, 0xFF835136};
    private CompoundTag view = new CompoundTag();
    private long cardsChanged, phaseChanged, positionsChanged;
    private int[] previousPositions = new int[4];
    private Component cardHover;

    void update(CompoundTag next) {
        long now = Util.getMillis();
        if (!view.getString("phase").equals(next.getString("phase"))) phaseChanged = now;
        if (!view.getList("playerCards", Tag.TAG_COMPOUND).equals(next.getList("playerCards", Tag.TAG_COMPOUND))
                || !view.getList("dealerCards", Tag.TAG_COMPOUND).equals(next.getList("dealerCards", Tag.TAG_COMPOUND))) cardsChanged = now;
        if (!Arrays.equals(view.getIntArray("positions"), next.getIntArray("positions"))) {
            previousPositions = view.getIntArray("positions"); positionsChanged = now;
        }
        view = next.copy();
    }

    void render(GuiGraphics g, Font font, GuildMenuKind kind, GamblingLayout l, int mouseX, int mouseY, boolean hover) {
        cardHover = null;
        switch (kind) {
            case BLACKJACK -> blackjack(g, font, l, mouseX, mouseY);
            case SLOT -> slot(g, font, l);
            case CHINCHIRORIN -> dice(g, font, l);
            case ROULETTE -> roulette(g, font, l);
            case COIN_FLIP -> coin(g, font, l);
            case HORSE_RACE -> race(g, font, l);
            default -> { }
        }
        if (hover && cardHover != null) g.renderTooltip(font, cardHover, mouseX, mouseY);
        if (hover && kind == GuildMenuKind.SLOT && leverHit(l, mouseX, mouseY))
            g.renderTooltip(font, Component.literal("賭け金を設定してレバーを引く"), mouseX, mouseY);
    }

    private void blackjack(GuiGraphics g, Font font, GamblingLayout l, int mx, int my) {
        int x = l.left(), y = l.boardY(), w = l.width(), h = l.boardHeight();
        GamblingArt.frame(g, x, y, w, h, 0xFF264D38);
        for (int dy = 8; dy < h - 6; dy += 4) g.hLine(x + 7, x + w - 8, y + dy, 0x12202D19);
        int row = (h - 14) / 2;
        hand(g, font, view.getList("dealerCards", Tag.TAG_COMPOUND), x + 10, y + 8, w - 20, row,
                "ディーラー", view.getInt("dealerTotal") + (phase("PLAYER_TURN") ? " + ?" : ""), mx, my);
        hand(g, font, view.getList("playerCards", Tag.TAG_COMPOUND), x + 10, y + 8 + row, w - 20, row,
                "あなた", Integer.toString(view.getInt("playerTotal")), mx, my);
        if (h > 160) {
            String text = "BLACKJACK  /  21";
            g.drawCenteredString(font, text, x + w / 2, y + h / 2 - 5, 0xFF91A77B);
            GamblingArt.item(g, new ItemStack(Items.GOLD_NUGGET), x + w - 34, y + h - 34, 20);
        }
    }

    private void hand(GuiGraphics g, Font font, ListTag cards, int x, int y, int w, int h,
                      String label, String value, int mx, int my) {
        int labelW = Math.min(86, w / 4), cardH = Math.max(22, Math.min(92, h - 8));
        int cardW = Math.max(18, Math.min(64, cardH * 2 / 3));
        g.drawString(font, label, x + 2, y + h / 2 - 10, 0xFFE5DDC3);
        if (!cards.isEmpty()) g.drawString(font, value, x + 2, y + h / 2 + 3, GamblingArt.GOLD);
        int count = cards.isEmpty() ? 2 : cards.size();
        int available = w - labelW - 8;
        int step = count > 1 ? Math.min(cardW + 7, (available - cardW) / (count - 1)) : 0;
        int start = x + labelW + Math.max(0, (available - cardW - step * (count - 1)) / 2);
        int cy = y + (h - cardH) / 2;
        for (int i = 0; i < count; i++) {
            CompoundTag card = cards.isEmpty() ? new CompoundTag() : cards.getCompound(i);
            boolean hidden = cards.isEmpty() || card.getBoolean("hidden");
            double t = cards.isEmpty() ? 1 : Math.max(0, Math.min(1, (Util.getMillis() - cardsChanged - i * 35) / 180.0));
            double ease = 1 - (1 - t) * (1 - t);
            int cx = (int)Math.round(x + w - cardW + (start + i * step - x - w + cardW) * ease);
            GamblingArt.card(g, font, cx, cy, cardW, cardH, card.getString("rank"), card.getString("suit"), hidden);
            if (mx >= cx && mx < cx + cardW && my >= cy && my < cy + cardH)
                cardHover = Component.literal(hidden ? "伏せ札" : card.getString("rank") + card.getString("suit"));
        }
    }

    private record Machine(int x, int y, int width, int height) { }
    private static Machine machine(GamblingLayout l) {
        return new Machine(l.left() + 8, l.boardY() + 4,
                l.width() >= 560 ? l.width() - 180 : l.width() - 16, l.boardHeight() - 8);
    }
    static boolean leverHit(GamblingLayout l, double x, double y) {
        Machine m = machine(l);
        return x >= m.x + m.width - 28 && x < m.x + m.width - 5 && y >= m.y + 9 && y < m.y + m.height - 8;
    }

    private void slot(GuiGraphics g, Font font, GamblingLayout l) {
        Machine m = machine(l);
        GamblingArt.frame(g, m.x, m.y, m.width, m.height, 0xFF302D27);
        int header = m.height >= 110 ? 23 : 8, bottom = m.height >= 110 ? 20 : 8;
        if (header > 8) g.drawCenteredString(font, "GUILD  SLOTS", m.x + (m.width - 20) / 2, m.y + 10, GamblingArt.GOLD);
        int rx = m.x + 12, ry = m.y + header, rw = m.width - 48, rh = m.height - header - bottom;
        g.fill(rx - 2, ry - 2, rx + rw + 2, ry + rh + 2, 0xFF171714);
        int reelW = (rw - 8) / 3, center = ry + rh / 2;
        int[] reels = view.getIntArray("reels");
        for (int i = 0; i < 3; i++) {
            int x = rx + i * (reelW + 4), symbol = i < reels.length ? reels[i] : -1;
            boolean spinning = phase("SPINNING") && symbol < 0;
            g.fill(x, ry, x + reelW, ry + rh, GamblingArt.CREAM);
            int size = Math.max(12, Math.min(38, rh / 3)), stride = Math.max(size + 5, rh / 3);
            double motion = spinning ? Util.getMillis() / (45.0 + i * 5) : 0;
            int base = spinning ? (int)Math.floor(motion) : symbol >= 0 ? symbol : i + 3;
            float offset = spinning ? (float)(motion - Math.floor(motion)) * stride : 0;
            g.enableScissor(x, ry, x + reelW, ry + rh);
            for (int row = -2; row <= 2; row++) {
                int index = Math.floorMod(base + row, SYMBOLS.length);
                GamblingArt.item(g, new ItemStack(SYMBOLS[index]), x + (reelW - size) / 2f,
                        center - size / 2f + row * stride - offset, size);
            }
            g.fill(x, ry, x + reelW, Math.max(ry, center - stride / 2), 0x402E291F);
            g.fill(x, Math.min(ry + rh, center + stride / 2), x + reelW, ry + rh, 0x402E291F);
            g.disableScissor();
            GamblingArt.outline(g, x, center - stride / 2, reelW, stride, phase("DONE") ? GamblingArt.GOLD : 0xFFAB5B3F);
            if (bottom > 8) {
                int color = symbol >= 0 ? 0xFF9DC372 : spinning ? 0xFFDCA849 : 0xFF686455;
                g.fill(x + reelW / 2 - 5, ry + rh + 6, x + reelW / 2 + 5, ry + rh + 9, color);
            }
        }
        g.hLine(rx - 5, rx - 1, center, 0xFFFF8060); g.hLine(rx + rw, rx + rw + 4, center, 0xFFFF8060);
        int leverX = m.x + m.width - 18, pivot = m.y + m.height / 2;
        int knobY = phase("SPINNING") ? m.y + m.height - 18 : m.y + 14;
        g.fill(leverX - 3, Math.min(knobY, pivot), leverX + 3, Math.max(knobY, pivot) + 3, 0xFFB7B1A1);
        g.fill(leverX - 6, pivot - 4, leverX + 6, pivot + 5, 0xFF6C6352);
        g.fill(leverX - 6, knobY - 4, leverX + 6, knobY + 5, 0xFF9C342A);
        g.fill(leverX - 4, knobY - 3, leverX + 4, knobY - 1, 0xFFD06645);
        if (l.width() >= 560) {
            int x = m.x + m.width + 12, y = m.y + 14;
            g.drawString(font, "3つ揃いの配当", x, y, GamblingArt.GOLD);
            for (int i = 0; i < SYMBOLS.length; i++) {
                GamblingArt.item(g, new ItemStack(SYMBOLS[i]), x, y + 18 + i * 23, 16);
                g.drawString(font, "× " + PAYOUTS[i], x + 25, y + 22 + i * 23, 0xFFEAE1C6);
            }
            g.drawString(font, "2つ揃いは返金", x, y + 162, 0xFFBBBBAB);
        }
    }

    private void dice(GuiGraphics g, Font font, GamblingLayout l) {
        GamblingArt.frame(g, l.left(), l.boardY(), l.width(), l.boardHeight(), 0xFF473125);
        int gap = 10, w = (l.width() - 3 * gap) / 2, h = l.boardHeight() - 16;
        for (int side = 0; side < 2; side++) {
            int x = l.left() + gap + side * (w + gap), y = l.boardY() + 8;
            g.fill(x, y, x + w, y + h, 0xFF30271F); GamblingArt.outline(g, x, y, w, h, 0xFF987343);
            String label = side == 0 ? "相手" : "あなた";
            g.drawCenteredString(font, label, x + w / 2, y + 5, GamblingArt.GOLD);
            int size = Math.max(14, Math.min(52, Math.min((w - 24) / 3, h - 28)));
            int[] values = view.getIntArray(side == 0 ? "dealerDice" : "playerDice");
            double t = Math.max(0, Math.min(1, (Util.getMillis() - phaseChanged) / 550.0));
            for (int i = 0; i < 3; i++) {
                int dx = x + w / 2 - (3 * size + 12) / 2 + i * (size + 6), dy = y + (h - size) / 2 + 4;
                int value = i < values.length ? values[i] : 0;
                if (phase("DONE") && t < 1) dy -= (int)(Math.abs(Math.sin(t * Math.PI * 3 + i)) * (1 - t) * size / 3);
                GamblingArt.die(g, dx, dy, size, value);
            }
            if (h > 95) g.drawCenteredString(font, view.getString(side == 0 ? "dealerHand" : "playerHand"),
                    x + w / 2, y + h - 18, 0xFFEAE1C6);
        }
    }

    private void roulette(GuiGraphics g, Font font, GamblingLayout l) {
        GamblingArt.frame(g, l.left(), l.boardY(), l.width(), l.boardHeight(), 0xFF244333);
        int available = l.boardHeight() - 40, radius = Math.max(17, Math.min(101, available / 2));
        int cx = l.left() + l.width() / 2, cy = l.boardY() + 8 + available / 2;
        GamblingArt.disk(g, cx + 2, cy + 3, radius, 0x70000000);
        GamblingArt.disk(g, cx, cy, radius, 0xFF36251A);
        GamblingArt.disk(g, cx, cy, radius - 3, 0xFFAA8144);
        GamblingArt.disk(g, cx, cy, Math.max(5, radius - 7), 0xFF463320);
        double rotation = phase("SPINNING") ? Util.getMillis() / 2100.0 : 0;
        int number = view.getInt("number"), winnerIndex = 0;
        for (int i = 0; i < WHEEL.length; i++) {
            if (WHEEL[i] == number) winnerIndex = i;
            double a = i * Math.PI * 2 / 37 - Math.PI / 2 + rotation;
            float px = cx + (float)Math.cos(a) * radius * 0.79f, py = cy + (float)Math.sin(a) * radius * 0.79f;
            int cellW = Math.max(2, (int)(radius * 0.14)), cellH = Math.max(6, (int)(radius * 0.27));
            g.pose().pushPose(); g.pose().translate(px, py, 0); g.pose().mulPose(Axis.ZP.rotation((float)a + (float)Math.PI / 2));
            g.fill(-cellW / 2, -cellH / 2, (cellW + 1) / 2, (cellH + 1) / 2, color(WHEEL[i]));
            if (phase("DONE") && WHEEL[i] == number) GamblingArt.outline(g, -cellW / 2 - 1, -cellH / 2 - 1,
                    cellW + 2, cellH + 2, GamblingArt.GOLD);
            if (radius > 58) {
                g.pose().scale(0.7f, 0.7f, 1);
                g.drawString(font, Integer.toString(WHEEL[i]), -font.width(Integer.toString(WHEEL[i])) / 2, -4, 0xFFFFFFFF, false);
            }
            g.pose().popPose();
        }
        GamblingArt.disk(g, cx, cy, Math.max(4, radius / 3), 0xFF2A382B);
        String text = phase("DONE") ? Integer.toString(number) : "?";
        g.drawCenteredString(font, text, cx, cy - 4, GamblingArt.GOLD);
        if (!phase("BETTING")) {
            double ball = phase("DONE") ? winnerIndex * Math.PI * 2 / 37 - Math.PI / 2 : -Util.getMillis() / 115.0;
            int bx = cx + (int)(Math.cos(ball) * radius * 0.55), by = cy + (int)(Math.sin(ball) * radius * 0.55);
            g.fill(bx - 2, by - 2, bx + 3, by + 3, 0xFFF5EBD1);
        }
    }

    private static int color(int n) { return n == 0 ? 0xFF36754D : Arrays.stream(RED).anyMatch(v -> v == n) ? 0xFF973C33 : 0xFF25272A; }

    private void coin(GuiGraphics g, Font font, GamblingLayout l) {
        GamblingArt.frame(g, l.left(), l.boardY(), l.width(), l.boardHeight(), 0xFF303C38);
        int size = Math.max(20, Math.min(110, l.boardHeight() - 44));
        int cx = l.left() + l.width() / 2, baseY = l.boardY() + (l.boardHeight() - 26) / 2;
        boolean flipping = phase("FLIPPING");
        double time = Util.getMillis() / 110.0;
        int cy = baseY - (flipping ? (int)(Math.abs(Math.sin(time / 2)) * size / 5) : 0);
        g.fill(cx - size / 3, baseY + size / 2 + 2, cx + size / 3, baseY + size / 2 + 5, 0x60000000);
        int side = phase("DONE") ? view.getInt("side") : flipping ? Math.cos(time) > 0 ? 0 : 1 : Math.max(0, view.getInt("choice"));
        int rim = side == 0 ? 0xFFAD7C2A : 0xFF777F80, face = side == 0 ? 0xFFE4C15C : 0xFFCED3CF;
        g.pose().pushPose(); g.pose().translate(cx, cy, 0);
        g.pose().scale(flipping ? Math.max(0.1f, (float)Math.abs(Math.cos(time))) : 1, 1, 1);
        GamblingArt.disk(g, 0, 0, size / 2, rim);
        GamblingArt.disk(g, 0, -1, Math.max(2, size / 2 - 3), face);
        int scale = Math.max(1, size / 12);
        String[] mark = side == 0 ? new String[]{"11111", "00111", "01010", "10000", "10000"}
                : new String[]{"01110", "11111", "11011", "11111", "01110"};
        GamblingArt.bitmap(g, mark, -5 * scale / 2, -5 * scale / 2, scale, rim);
        g.pose().popPose();
        if (l.boardHeight() > 140) g.drawCenteredString(font, "選んだ面が出れば 2倍", cx, l.boardY() + 13, GamblingArt.GOLD);
        if (phase("DONE") && l.boardHeight() >= 190)
            g.drawCenteredString(font, side == 0 ? "出目: 表 (オモテ)" : "出目: 裏 (ウラ)", cx,
                    baseY + size / 2 + 9, GamblingArt.GOLD);
    }

    private void race(GuiGraphics g, Font font, GamblingLayout l) {
        GamblingArt.frame(g, l.left(), l.boardY(), l.width(), l.boardHeight(), 0xFF42613B);
        int row = Math.max(12, (l.boardHeight() - 16) / 4), start = l.left() + 98;
        int finish = l.right() - 24, trackWidth = finish - start - 24;
        int[] positions = view.getIntArray("positions");
        for (int i = 0; i < 4; i++) {
            int y = l.boardY() + 8 + i * row;
            g.fill(start, y, finish + 8, y + row - 2, i % 2 == 0 ? 0xFF735640 : 0xFF664A36);
            g.hLine(start, finish + 8, y, 0xFFB2986A);
            for (int dy = 0; dy < row - 2; dy += 4) for (int dx = 0; dx < 8; dx += 4)
                g.fill(finish + dx, y + dy, finish + dx + 4, y + Math.min(row - 2, dy + 4),
                        (dx / 4 + dy / 4) % 2 == 0 ? 0xFFEEE3CB : 0xFF2D2C28);
            int scale = Math.max(1, Math.min(3, (row - 4) / 13));
            int target = i < positions.length ? positions[i] : 0;
            float t = Math.max(0, Math.min(1, (Util.getMillis() - positionsChanged) / 50f));
            float from = i < previousPositions.length ? previousPositions[i] : target;
            float position = phase("RACING") ? from + (target - from) * t : target;
            int hx = start + (int)(Math.max(0, Math.min(100, position)) * Math.max(1, trackWidth - 17 * scale) / 100);
            int hy = y + Math.max(0, (row - 13 * scale) / 2);
            GamblingArt.horse(g, hx, hy, scale, COATS[i], phase("RACING"), Util.getMillis() / 85.0 + i);
            if (phase("DONE") && view.getInt("winner") == i) GamblingArt.outline(g, start - 2, y, finish - start + 12, row - 2, GamblingArt.GOLD);
            if (row >= 28) g.drawString(font, target + "%", finish - 26, y + 3, 0xFFEAE1C6);
        }
    }

    private boolean phase(String phase) { return view.getString("phase").equals(phase); }
}
