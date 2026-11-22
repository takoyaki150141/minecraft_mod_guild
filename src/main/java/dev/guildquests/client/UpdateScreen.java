package dev.guildquests.client;

import dev.guildquests.update.UpdateChecker;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Full-screen popup shown on the title screen when a mod update is available. */
public class UpdateScreen extends Screen {
    private final Screen parent;
    private final String currentVersion;
    private final UpdateChecker.UpdateInfo info;

    private enum State { READY, DOWNLOADING, DONE, SCHEDULED, STAGED, FAILED }

    private volatile State state = State.READY;

    public UpdateScreen(Screen parent, String currentVersion, UpdateChecker.UpdateInfo info) {
        super(Component.literal("GuildQuests Update"));
        this.parent = parent;
        this.currentVersion = currentVersion;
        this.info = info;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int cy = this.height / 2;

        Button downloadBtn = Button.builder(Component.literal("ダウンロード"), b -> startDownload())
                .bounds(cx - 100, cy + 30, 98, 20).build();
        this.addRenderableWidget(downloadBtn);

        Button closeBtn = Button.builder(Component.literal("閉じる"), b -> {
            if (this.minecraft != null) {
                this.minecraft.setScreen(parent);
            }
        }).bounds(cx + 2, cy + 30, 98, 20).build();
        this.addRenderableWidget(closeBtn);
    }

    private void startDownload() {
        if (state != State.READY) return;
        state = State.DOWNLOADING;
        new Thread(() -> {
            UpdateChecker.DownloadResult result = UpdateChecker.download(info,
                    dev.guildquests.platform.Platform.getModsDir(),
                    dev.guildquests.platform.Platform.getLoaderName());
            state = switch (result) {
                case OK -> State.DONE;
                case SCHEDULED -> State.SCHEDULED;
                case STAGED -> State.STAGED;
                case FAILED -> State.FAILED;
            };
        }, "GuildQuests-UpdateDownload").start();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        // 1.21's renderBackground blurs the backdrop into frosted glass; draw the standard
        // title panorama plus a plain translucent tint instead, like the 1.20.1 build did.
        this.renderPanorama(g, delta);
        this.renderTransparentBackground(g);
        int cx = this.width / 2;
        int cy = this.height / 2;

        g.drawCenteredString(this.font, "=== GuildQuests アップデート ===", cx, cy - 60, 0xFFD700);
        g.drawCenteredString(this.font, "現在のバージョン: v" + currentVersion, cx, cy - 40, 0xAAAAAA);
        g.drawCenteredString(this.font, "最新バージョン: v" + info.latestVersion(), cx, cy - 25, 0x55FF55);

        switch (state) {
            case READY -> g.drawCenteredString(this.font, "ダウンロードして再起動すると適用されます", cx, cy + 5, 0xFFFFFF);
            case DOWNLOADING -> g.drawCenteredString(this.font, "ダウンロード中...", cx, cy + 5, 0xFFFF55);
            case DONE -> g.drawCenteredString(this.font, "完了！Minecraftを再起動してください", cx, cy + 5, 0x55FF55);
            case SCHEDULED -> {
                g.drawCenteredString(this.font, "ダウンロード完了（ゲーム終了時に自動で置き換えます）", cx, cy + 5, 0x55FF55);
                g.drawCenteredString(this.font, "ゲームを終了してから、次回起動で新バージョンが適用されます", cx, cy + 20, 0xFFFFFF);
            }
            case STAGED -> {
                g.drawCenteredString(this.font, "ダウンロード完了（手動での置き換えが必要です）", cx, cy + 5, 0xFFFF55);
                g.drawCenteredString(this.font, "ゲーム終了後、mods内の古いJARを削除し、", cx, cy + 20, 0xFFFFFF);
                g.drawCenteredString(this.font, "config/guildquests/update/ の新しいJARをmodsへ移動してください", cx, cy + 33, 0xFFFFFF);
            }
            case FAILED -> g.drawCenteredString(this.font, "ダウンロードに失敗しました", cx, cy + 5, 0xFF5555);
        }

        super.render(g, mouseX, mouseY, delta);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
