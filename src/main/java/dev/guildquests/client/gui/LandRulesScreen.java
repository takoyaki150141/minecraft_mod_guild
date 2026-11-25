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
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Protection switches for the player's own land. The server owns the values; the screen
 * only sends menu button clicks and re-reads the synced view every tick.
 */
public final class LandRulesScreen extends Screen implements MenuAccess<GuildClientMenu> {
    private final GuildClientMenu menu;
    private final Screen parent;
    private final Map<Integer, Button> toggles = new LinkedHashMap<>();
    private int mask;

    public LandRulesScreen(GuildClientMenu menu, Screen parent, Component title) {
        super(title);
        this.menu = menu;
        this.parent = parent;
    }

    @Override public GuildClientMenu getMenu() { return menu; }

    @Override public boolean isPauseScreen() { return false; }

    @Override protected void init() {
        readMask();
        int margin = Math.max(8, Math.min(24, width / 40));
        int left = margin;
        int contentWidth = Math.max(120, width - margin * 2);
        int gap = 6;
        int buttonWidth = Math.min(240, (contentWidth - gap) / 2);
        int rows = (LandRules.RULES.size() + 1) / 2;
        int top = 36;
        int rowHeight = Math.max(18, Math.min(24, (height - top - 34) / Math.max(1, rows)));
        int buttonHeight = Math.max(14, rowHeight - 4);

        for (int index = 0; index < LandRules.RULES.size(); index++) {
            LandRules.Rule rule = LandRules.RULES.get(index);
            int x = left + (index % 2) * (buttonWidth + gap);
            int y = top + (index / 2) * rowHeight;
            int id = index;
            Button button = addRenderableWidget(Button.builder(label(rule), b -> toggle(id))
                    .bounds(x, y, buttonWidth, buttonHeight).build());
            toggles.put(index, button);
        }

        int backWidth = Math.min(160, contentWidth);
        addRenderableWidget(Button.builder(Component.literal("戻る"), b -> back())
                .bounds((width - backWidth) / 2, Math.min(height - 26, top + rows * rowHeight + 8),
                        backWidth, 20).build());
        updateLabels();
    }

    @Override public void tick() {
        if (minecraft.player == null || !minecraft.player.isAlive() || minecraft.player.isRemoved()) {
            back();
            return;
        }
        int previous = mask;
        readMask();
        if (previous != mask) updateLabels();
    }

    private void readMask() {
        CompoundTag tag = GuildItems.tag(menu.getSlot(0).getItem());
        CompoundTag view = tag.contains(LandView.TAG) ? tag.getCompound(LandView.TAG) : new CompoundTag();
        mask = view.getInt("ruleMask");
    }

    private void updateLabels() {
        for (Map.Entry<Integer, Button> entry : toggles.entrySet()) {
            LandRules.Rule rule = LandRules.byIndex(entry.getKey());
            if (rule != null) entry.getValue().setMessage(label(rule));
        }
    }

    private Component label(LandRules.Rule rule) {
        boolean on = LandRules.has(mask, rule.bit());
        return Component.literal((on ? "[ON] " : "[OFF] ") + rule.label())
                .withStyle(on ? ChatFormatting.GREEN : ChatFormatting.GRAY);
    }

    private void toggle(int index) {
        if (minecraft.gameMode == null) return;
        minecraft.gameMode.handleInventoryButtonClick(menu.containerId,
                LandScreenHandler.RULE_BUTTON_BASE + index);
    }

    private void back() {
        minecraft.setScreen(parent);
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderTransparentBackground(graphics);
        int margin = Math.max(8, Math.min(24, width / 40));
        graphics.drawString(font, "保護設定（自分の土地）", margin, 10, 0xFFFFFF);
        graphics.drawString(font, fit("切り替えた内容は自分の所有チャンクに適用されます",
                width - margin * 2), margin, 22, 0xA0A0A0);
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
