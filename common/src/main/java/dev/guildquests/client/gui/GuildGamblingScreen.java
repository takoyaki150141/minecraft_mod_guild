package dev.guildquests.client.gui;

import dev.guildquests.gui.GuildClientMenu;
import dev.guildquests.gui.GuildGamblingView;
import dev.guildquests.gui.GuildMenuKind;
import dev.guildquests.util.Numbers;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/** A playable table or machine, with all actions still owned by the original server menu. */
public final class GuildGamblingScreen extends GuildMenuScreen {
    private final GamblingTableRenderer table = new GamblingTableRenderer();
    private GamblingLayout layout;
    private CompoundTag view = new CompoundTag();
    private String controlsKey = "";
    private boolean bettingOpen;
    private int popupX, popupY, popupWidth, popupHeight;

    GuildGamblingScreen(GuildClientMenu menu, Inventory inventory, Component title) { super(menu, inventory, title); }

    @Override protected void init() {
        layout = GamblingLayout.of(width, height);
        readView();
        rebuildControls();
    }

    @Override public void tick() {
        if (minecraft.player == null || !minecraft.player.isAlive() || minecraft.player.isRemoved()) { onClose(); return; }
        readView();
        if (!controlsKey.equals(controlSignature())) rebuildControls();
    }

    private void readView() {
        var tag = stack(0).getTag();
        view = tag != null && tag.contains(GuildGamblingView.TAG) ? tag.getCompound(GuildGamblingView.TAG) : new CompoundTag();
        table.update(view);
    }

    private String controlSignature() {
        int main = kind() == GuildMenuKind.HORSE_RACE ? 49 : 25;
        return view.getString("phase") + ":" + view.getLong("bet") + ":" + view.getLong("balance")
                + ":" + view.getInt("choice") + ":" + stack(main).getHoverName().getString() + ":" + action(main);
    }

    private GuildMenuKind kind() { return getMenu().kind; }
    private ItemStack stack(int slot) { return getMenu().getSlot(slot).getItem(); }
    private boolean action(int slot) { var tag = stack(slot).getTag(); return tag != null && tag.getBoolean("GuildGuiAction"); }
    private boolean betting() { return view.getString("phase").equals("BETTING"); }

    private void rebuildControls() {
        clearWidgets();
        controlsKey = controlSignature();
        if (bettingOpen) { buildBettingPopup(); return; }
        int left = layout.left(), w = layout.width();
        button("戻る", left, layout.footerY(), 64, 20,
                () -> minecraft.gameMode.handleInventoryButtonClick(getMenu().containerId, 0));
        button("閉じる", layout.right() - 64, layout.footerY(), 64, 20, this::onClose);
        if (layout.compact() || kind() == GuildMenuKind.HORSE_RACE)
            button(kind() == GuildMenuKind.HORSE_RACE ? "馬・賭け金" : "賭け金", left + 70, layout.footerY(), 80, 20,
                    () -> { bettingOpen = true; rebuildControls(); });
        Button rules = button("ルール", layout.right() - 114, layout.footerY(), 44, 20, () -> { });
        rules.setTooltip(Tooltip.create(Component.literal(rules())));
        if (!layout.compact()) betButtons(left, layout.betY(), w);

        if (kind() == GuildMenuKind.BLACKJACK && view.getString("phase").equals("PLAYER_TURN")) {
            int bw = Math.min(160, (w - 6) / 2), center = left + w / 2;
            serverButton(18, center - bw - 3, layout.actionY(), bw, 20, "ヒット");
            serverButton(19, center + 3, layout.actionY(), bw, 20, "スタンド");
        } else {
            int slot = kind() == GuildMenuKind.HORSE_RACE ? 49 : 25;
            int bw = Math.min(260, w);
            serverButton(slot, left + (w - bw) / 2, layout.actionY(), bw, 20, stack(slot).getHoverName().getString());
        }

        if (kind() == GuildMenuKind.COIN_FLIP || kind() == GuildMenuKind.ROULETTE) {
            int[] slots = kind() == GuildMenuKind.COIN_FLIP ? new int[]{11, 15} : new int[]{11, 13, 15};
            String[] labels = kind() == GuildMenuKind.COIN_FLIP ? new String[]{"表 (オモテ)", "裏 (ウラ)"}
                    : new String[]{"赤 (2倍)", "ゼロ (36倍)", "黒 (2倍)"};
            int bw = Math.min(125, (w - 32 - (slots.length - 1) * 6) / slots.length);
            int start = left + (w - (bw + 6) * slots.length + 6) / 2;
            for (int i = 0; i < slots.length; i++) {
                int choice = kind() == GuildMenuKind.COIN_FLIP ? i : new int[]{0, 2, 1}[i];
                String prefix = view.getInt("choice") == choice ? "● " : "";
                serverButton(slots[i], start + i * (bw + 6), layout.boardBottom() - 28, bw, 20, prefix + labels[i]);
            }
        }
        if (kind() == GuildMenuKind.HORSE_RACE) horseButtons(left + 10, layout.boardY() + 8, 4,
                Math.max(12, (layout.boardHeight() - 16) / 4), 78);
    }

    private void horseButtons(int x, int y, int count, int rowHeight, int buttonWidth) {
        var names = view.getList("horses", net.minecraft.nbt.Tag.TAG_STRING);
        int[] odds = view.getIntArray("odds");
        for (int i = 0; i < Math.min(count, names.size()); i++) {
            String name = names.getString(i) + " ×" + (i < odds.length ? odds[i] : "?");
            if (view.getInt("choice") == i) name = "● " + name;
            serverButton(i, x, y + i * rowHeight, buttonWidth, Math.min(20, rowHeight - 2), name);
        }
    }

    private void betButtons(int x, int y, int width) {
        int[] increments = {10, 50, 100, 500};
        int group = (width - 18) / 4, bw = (group - 3) / 2;
        int base = kind() == GuildMenuKind.HORSE_RACE ? 36 : 18;
        for (int i = 0; i < 4; i++) {
            int slot = base + i, increment = increments[i];
            Button minus = button("-" + Numbers.format(increment), x + i * (group + 6), y, bw, 20, () -> clickSlot(slot, 1));
            Button plus = button("+" + Numbers.format(increment), x + i * (group + 6) + bw + 3, y, bw, 20, () -> clickSlot(slot, 0));
            minus.active = betting() && action(slot) && view.getLong("bet") > 0;
            plus.active = betting() && action(slot) && view.getLong("bet") < view.getLong("balance");
            minus.setTooltip(Tooltip.create(Component.literal("賭け金を " + Numbers.format(increment) + " コイン減らす")));
            plus.setTooltip(Tooltip.create(Component.literal("賭け金を " + Numbers.format(increment) + " コイン増やす")));
        }
    }

    private void buildBettingPopup() {
        popupWidth = Math.min(400, layout.width());
        popupHeight = kind() == GuildMenuKind.HORSE_RACE ? 142 : 96;
        popupX = (width - popupWidth) / 2; popupY = (height - popupHeight) / 2;
        int betY = popupY + 34;
        if (kind() == GuildMenuKind.HORSE_RACE) {
            var names = view.getList("horses", net.minecraft.nbt.Tag.TAG_STRING);
            int[] odds = view.getIntArray("odds");
            for (int i = 0; i < Math.min(4, names.size()); i++) {
                String name = (view.getInt("choice") == i ? "● " : "") + names.getString(i) + " ×" + odds[i];
                serverButton(i, popupX + 10 + i % 2 * (popupWidth / 2 - 5), popupY + 32 + i / 2 * 24,
                        popupWidth / 2 - 15, 20, name);
            }
            betY = popupY + 84;
        }
        betButtons(popupX + 10, betY, popupWidth - 20);
        button(kind() == GuildMenuKind.HORSE_RACE ? "レース" : "戻る", popupX + (popupWidth - 100) / 2,
                popupY + popupHeight - 28, 100, 20, () -> { bettingOpen = false; rebuildControls(); });
    }

    private Button button(String label, int x, int y, int w, int h, Runnable run) {
        Button b = Button.builder(Component.literal(fit(label, w - 8)), ignored -> run.run()).bounds(x, y, w, h).build();
        if (font.width(label) > w - 8) b.setTooltip(Tooltip.create(Component.literal(label)));
        return addRenderableWidget(b);
    }

    private void serverButton(int slot, int x, int y, int w, int h, String label) {
        Button b = button(label.isBlank() ? "読み込み中…" : label, x, y, w, h, () -> clickSlot(slot, 0));
        b.active = action(slot);
        List<Component> lines = stack(slot).getTooltipLines(minecraft.player, TooltipFlag.Default.NORMAL);
        b.setTooltip(Tooltip.create(Component.literal(String.join("\n", lines.stream().map(Component::getString).toList()))));
    }

    private void clickSlot(int slot, int button) {
        if (minecraft.player != null && minecraft.gameMode != null)
            minecraft.gameMode.handleInventoryMouseClick(getMenu().containerId, slot, button, ClickType.PICKUP, minecraft.player);
    }

    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        g.drawString(font, title, layout.left(), layout.titleY(), 0xFFEAE1C6);
        String phase = view.getString("phase");
        String status = switch (phase) {
            case "BETTING" -> "賭け金を選んでスタート";
            case "PLAYER_TURN" -> "あなたの番";
            case "DONE" -> view.getString("result") + " / 払戻 " + coins(view.getLong("payout")) + " GC";
            case "RACING" -> "レース中…";
            case "SPINNING", "FLIPPING" -> "抽選中…";
            default -> "読み込み中…";
        };
        if (phase.equals("DONE") && kind() == GuildMenuKind.COIN_FLIP)
            status = (view.getInt("side") == 0 ? "表 / " : "裏 / ") + status;
        String shortStatus = fit(status, layout.width() - font.width(title) - 16);
        g.drawString(font, shortStatus, layout.right() - font.width(shortStatus), layout.titleY(), GamblingArt.GOLD);
        table.render(g, font, kind(), layout, mouseX, mouseY, !bettingOpen);
        g.drawString(font, fit("賭け金: " + coins(view.getLong("bet")) + " GC", layout.width() / 2 - 6),
                layout.left(), layout.summaryY(), GamblingArt.GOLD);
        String balance = fit("所持: " + coins(view.getLong("balance")) + " GC", layout.width() / 2 - 6);
        g.drawString(font, balance, layout.right() - font.width(balance), layout.summaryY(), 0xFFDDDDCC);
        if (bettingOpen) {
            g.fill(0, 0, width, height, 0xA0000000);
            GamblingArt.frame(g, popupX, popupY, popupWidth, popupHeight, 0xFF39362D);
            g.drawCenteredString(font, "賭け金: " + coins(view.getLong("bet")) + " GC", width / 2, popupY + 12, GamblingArt.GOLD);
        }
        for (var child : children()) if (child instanceof net.minecraft.client.gui.components.Renderable widget)
            widget.render(g, mouseX, mouseY, partialTick);
        if (!bettingOpen && mouseY >= layout.titleY() && mouseY < layout.titleY() + 10 && mouseX > layout.left() + font.width(title))
            g.renderTooltip(font, Component.literal(status), mouseX, mouseY);
    }

    private String fit(String text, int width) {
        return font.width(text) <= width ? text : font.plainSubstrByWidth(text, Math.max(0, width - font.width("…"))) + "…";
    }
    private static String coins(long amount) { return Numbers.format(amount); }

    private String rules() {
        return switch (kind()) {
            case BLACKJACK -> "ブラックジャック\n21を超えずにディーラーより大きい手を目指します。\nヒット: 1枚引く / スタンド: 現在の手で勝負\nAは1または11、絵札は10。相手は17以上で止まります。\n勝ち: 2倍 / ブラックジャック: 2.5倍 / 引分: 返金";
            case CHINCHIRORIN -> "チンチロ\n3個のサイコロで相手と勝負します。\n強い順: アリ(111) → ゾロ目 → 目 → バン\n勝ち: 2倍 / 引分: 返金";
            case SLOT -> "スロット・配当表\n中央の横1列で判定します。リールは左から止まります。\n3つ揃い: ダイヤ80倍 / エメラルド50倍 / 金25倍\n鉄12倍 / りんご6倍 / 棒4倍\n2つ揃い: 賭け金返金";
            case ROULETTE -> "ルーレット\n赤・黒・ゼロを選んで回します。\n赤・黒: 2倍 / ゼロ: 36倍\n0は赤にも黒にも含まれません。";
            case COIN_FLIP -> "コイントス\n表または裏を選んでコインを投げます。\n選んだ面が出たら2倍。";
            case HORSE_RACE -> "競馬\n馬を選び、賭け金を設定してレースを開始します。\n白馬3倍 / 黒馬5倍 / 栗毛2倍 / 鹿毛8倍\n選んだ馬が1着になると配当を受け取れます。";
            default -> "";
        };
    }

    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == GLFW.GLFW_KEY_ESCAPE && bettingOpen) { bettingOpen = false; rebuildControls(); return true; }
        if (key == GLFW.GLFW_KEY_PAGE_UP || key == GLFW.GLFW_KEY_PAGE_DOWN) return false;
        return super.keyPressed(key, scan, modifiers);
    }
    @Override public boolean mouseClicked(double x, double y, int button) {
        if (!bettingOpen && kind() == GuildMenuKind.SLOT && betting() && button == 0 && action(25)
                && GamblingTableRenderer.leverHit(layout, x, y)) { clickSlot(25, 0); return true; }
        return super.mouseClicked(x, y, button);
    }
    @Override public boolean mouseScrolled(double x, double y, double delta) { return false; }
}
