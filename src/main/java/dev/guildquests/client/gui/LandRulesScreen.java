package dev.guildquests.client.gui;

import dev.guildquests.gui.GuildClientMenu;
import dev.guildquests.gui.GuildItems;
import dev.guildquests.gui.LandScreenHandler;
import dev.guildquests.gui.LandView;
import dev.guildquests.service.LandRules;
import net.minecraft.ChatFormatting;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Protection switches and the whitelist for the player's own land. The server owns the values;
 * the screen only sends menu button clicks and re-reads the synced view every tick.
 */
public final class LandRulesScreen extends Screen implements MenuAccess<GuildClientMenu> {
    private final GuildClientMenu menu;
    private final Screen parent;
    private final Map<Integer, Button> toggles = new LinkedHashMap<>();
    private int mask;
    private boolean trustMode;
    private List<TrustRow> trust = List.of();
    private String signature = "";

    /** One whitelist row as published by the server. */
    private record TrustRow(String name, boolean allowed) { }

    public LandRulesScreen(GuildClientMenu menu, Screen parent, Component title) {
        super(title);
        this.menu = menu;
        this.parent = parent;
    }

    @Override public GuildClientMenu getMenu() { return menu; }

    @Override public boolean isPauseScreen() { return false; }

    @Override protected void init() {
        readView();
        rebuild();
    }

    @Override public void tick() {
        if (minecraft.player == null || !minecraft.player.isAlive() || minecraft.player.isRemoved()) {
            back();
            return;
        }
        readView();
        if (!signature.equals(currentSignature())) rebuild();
    }

    private void readView() {
        CompoundTag tag = GuildItems.tag(menu.getSlot(0).getItem());
        CompoundTag view = tag.contains(LandView.TAG) ? tag.getCompound(LandView.TAG) : new CompoundTag();
        mask = view.getInt("ruleMask");
        List<TrustRow> rows = new ArrayList<>();
        ListTag list = view.getList("trust", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag row = list.getCompound(i);
            rows.add(new TrustRow(row.getString("name"), row.getBoolean("allowed")));
        }
        trust = List.copyOf(rows);
    }

    private String currentSignature() {
        StringBuilder builder = new StringBuilder().append(mask).append('|').append(trustMode);
        for (TrustRow row : trust) builder.append('|').append(row.name()).append(row.allowed());
        return builder.toString();
    }

    private void rebuild() {
        clearWidgets();
        toggles.clear();
        signature = currentSignature();

        int margin = Math.max(8, Math.min(24, width / 40));
        int left = margin;
        int contentWidth = Math.max(120, width - margin * 2);

        int tabWidth = Math.min(140, (contentWidth - 6) / 2);
        Button rulesTab = addRenderableWidget(Button.builder(Component.literal("保護設定"), b -> switchMode(false))
                .bounds(left, 34, tabWidth, 18).build());
        rulesTab.active = trustMode;
        Button trustTab = addRenderableWidget(Button.builder(Component.literal("ホワイトリスト"), b -> switchMode(true))
                .bounds(left + tabWidth + 6, 34, tabWidth, 18).build());
        trustTab.active = !trustMode;

        int top = 60;
        if (trustMode) {
            buildTrustRows(left, contentWidth, top);
        } else {
            buildRuleRows(left, contentWidth, top);
        }

        int backWidth = Math.min(160, contentWidth);
        addRenderableWidget(Button.builder(Component.literal("戻る"), b -> back())
                .bounds((width - backWidth) / 2, Math.min(height - 26, height - 30), backWidth, 20).build());
    }

    private void switchMode(boolean trustMode) {
        this.trustMode = trustMode;
        rebuild();
    }

    private void buildRuleRows(int left, int contentWidth, int top) {
        int gap = 6;
        int buttonWidth = Math.min(240, (contentWidth - gap) / 2);
        int rows = (LandRules.RULES.size() + 1) / 2;
        int rowHeight = Math.max(18, Math.min(24, (height - top - 34) / Math.max(1, rows)));
        int buttonHeight = Math.max(14, rowHeight - 4);
        for (int index = 0; index < LandRules.RULES.size(); index++) {
            LandRules.Rule rule = LandRules.RULES.get(index);
            int x = left + (index % 2) * (buttonWidth + gap);
            int y = top + (index / 2) * rowHeight;
            int id = index;
            Button button = addRenderableWidget(Button.builder(label(rule), b -> toggleRule(id))
                    .bounds(x, y, buttonWidth, buttonHeight).build());
            toggles.put(index, button);
        }
    }

    private void buildTrustRows(int left, int contentWidth, int top) {
        if (trust.isEmpty()) return;
        int gap = 6;
        int buttonWidth = Math.min(240, (contentWidth - gap) / 2);
        int rows = Math.min(6, (trust.size() + 1) / 2);
        int rowHeight = Math.max(18, Math.min(24, (height - top - 34) / Math.max(1, rows)));
        int buttonHeight = Math.max(14, rowHeight - 4);
        int shown = Math.min(trust.size(), rows * 2);
        for (int index = 0; index < shown; index++) {
            TrustRow row = trust.get(index);
            int x = left + (index % 2) * (buttonWidth + gap);
            int y = top + (index / 2) * rowHeight;
            int id = index;
            Button button = addRenderableWidget(Button.builder(trustLabel(row), b -> toggleTrust(id))
                    .bounds(x, y, buttonWidth, buttonHeight).build());
            toggles.put(index, button);
        }
    }

    private Component label(LandRules.Rule rule) {
        boolean on = LandRules.has(mask, rule.bit());
        return Component.literal((on ? "[ON] " : "[OFF] ") + rule.label())
                .withStyle(on ? ChatFormatting.GREEN : ChatFormatting.GRAY);
    }

    private Component trustLabel(TrustRow row) {
        return Component.literal((row.allowed() ? "[許可] " : "[不可] ") + row.name())
                .withStyle(row.allowed() ? ChatFormatting.GREEN : ChatFormatting.GRAY);
    }

    private void toggleRule(int index) {
        if (minecraft.gameMode == null) return;
        minecraft.gameMode.handleInventoryButtonClick(menu.containerId,
                LandScreenHandler.RULE_BUTTON_BASE + index);
    }

    private void toggleTrust(int index) {
        if (minecraft.gameMode == null) return;
        minecraft.gameMode.handleInventoryButtonClick(menu.containerId,
                LandScreenHandler.TRUST_BUTTON_BASE + index);
    }

    private void back() {
        minecraft.setScreen(parent);
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderTransparentBackground(graphics);
        int margin = Math.max(8, Math.min(24, width / 40));
        String title = trustMode ? "ホワイトリスト（自分の土地）" : "保護設定（自分の土地）";
        graphics.drawString(font, title, margin, 10, 0xFFFFFF);
        String hint = trustMode
                ? "許可したプレイヤーは、自分の土地で保護を無視できます"
                : "切り替えた内容は自分の所有チャンクに適用されます";
        graphics.drawString(font, fit(hint, width - margin * 2), margin, 22, 0xA0A0A0);
        if (trustMode && trust.isEmpty()) {
            graphics.drawCenteredString(font, "ほかのオンラインプレイヤーがいません", width / 2, 74, 0xCCCCCC);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private String fit(String value, int maxWidth) {
        if (font.width(value) <= maxWidth) return value;
        return font.plainSubstrByWidth(value, Math.max(0, maxWidth - font.width("…"))) + "…";
    }

    @Override public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            back();
            return true;
        }
        return super.keyPressed(key, scanCode, modifiers);
    }
}
