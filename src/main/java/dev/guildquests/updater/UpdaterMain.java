package dev.guildquests.updater;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Standalone update window, started by the Mod as a separate JVM.
 * <p>
 * Kept free of Minecraft classes on purpose: it runs after the game has copied the mod JAR
 * outside the mods folder, so the running JAR can still be replaced.
 */
public final class UpdaterMain {
    private UpdaterMain() { }

    public static void main(String[] args) {
        if (args.length < 1) {
            System.exit(2);
        }
        UpdaterInstall.UpdaterContext context;
        try {
            context = load(Path.of(args[0]));
        } catch (Exception e) {
            showError("更新情報を読み込めませんでした:\n" + e.getMessage());
            return;
        }
        SwingUtilities.invokeLater(() -> show(context));
    }

    static UpdaterInstall.UpdaterContext load(Path file) throws IOException {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            p.load(in);
        }
        return new UpdaterInstall.UpdaterContext(
                required(p, "currentVersion"),
                required(p, "latestVersion"),
                required(p, "loader"),
                p.getProperty("minecraftVersion", "1.21.1"),
                required(p, "assetUrl"),
                Long.parseLong(p.getProperty("assetSize", "-1")),
                required(p, "sha256"),
                Path.of(required(p, "modsDir")),
                Path.of(required(p, "currentJar")),
                required(p, "currentSha256"),
                Path.of(required(p, "stagingDir")),
                Long.parseLong(required(p, "parentPid")),
                required(p, "parentStartedAt"),
                Path.of(required(p, "selfJar")));
    }

    private static String required(Properties p, String key) throws IOException {
        String value = p.getProperty(key);
        if (value == null || value.isBlank()) throw new IOException("項目がありません: " + key);
        return value;
    }

    private static void show(UpdaterInstall.UpdaterContext context) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
        }
        new Window(context).open();
    }

    /** Essential-style small window: version, size, progress and two buttons. */
    private static final class Window {
        private static final Color TEXT = new Color(0x22, 0x22, 0x22);
        private static final Color MUTED = new Color(0x66, 0x66, 0x66);
        private static final Color ACCENT = new Color(0x2E, 0x6B, 0x4F);

        private final UpdaterInstall.UpdaterContext context;
        private final JFrame frame = new JFrame("Guild Quests & Economy - アップデート");
        private final JProgressBar progress = new JProgressBar(0, 100);
        private final JLabel status = new JLabel(" ");
        private final JButton update = new JButton("更新する");
        private final JButton ignore = new JButton("無視して起動");
        private final JButton release = new JButton("リリースページ");
        private boolean running;

        Window(UpdaterInstall.UpdaterContext context) {
            this.context = context;
        }

        void open() {
            frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
            frame.setContentPane(buildContent());
            frame.pack();
            frame.setMinimumSize(new Dimension(500, 300));
            frame.setSize(new Dimension(500, 300));
            frame.setLocationRelativeTo(null);
            frame.setResizable(true);
            frame.addWindowListener(new WindowAdapter() {
                @Override public void windowClosing(WindowEvent e) {
                    if (!running) ignoreAndStart();
                }
            });
            // The game waits for this window, so stay above whatever it shows.
            frame.setAlwaysOnTop(true);
            frame.setVisible(true);
            frame.toFront();
            frame.requestFocus();
        }

        private JPanel buildContent() {
            JPanel root = new JPanel(new BorderLayout());
            root.setBackground(Color.WHITE);
            root.setBorder(BorderFactory.createEmptyBorder(18, 22, 16, 22));

            JPanel header = new JPanel();
            header.setOpaque(false);
            header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
            JLabel title = new JLabel("Guild Quests & Economy");
            title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));
            title.setForeground(TEXT);
            JLabel subtitle = new JLabel("新しいバージョンがあります");
            subtitle.setFont(subtitle.getFont().deriveFont(Font.PLAIN, 12f));
            subtitle.setForeground(MUTED);
            header.add(title);
            header.add(Box.createVerticalStrut(2));
            header.add(subtitle);
            header.add(Box.createVerticalStrut(14));

            JLabel versions = new JLabel("v" + context.currentVersion() + "  →  v" + context.latestVersion());
            versions.setFont(versions.getFont().deriveFont(Font.BOLD, 15f));
            versions.setForeground(ACCENT);
            JLabel meta = new JLabel(describeTarget());
            meta.setFont(meta.getFont().deriveFont(Font.PLAIN, 12f));
            meta.setForeground(MUTED);
            header.add(versions);
            header.add(Box.createVerticalStrut(2));
            header.add(meta);
            root.add(header, BorderLayout.NORTH);

            JPanel center = new JPanel();
            center.setOpaque(false);
            center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
            center.setBorder(BorderFactory.createEmptyBorder(16, 0, 12, 0));
            progress.setStringPainted(false);
            progress.setValue(0);
            progress.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
            progress.setAlignmentX(0f);
            status.setFont(status.getFont().deriveFont(Font.PLAIN, 12f));
            status.setForeground(MUTED);
            status.setAlignmentX(0f);
            center.add(progress);
            center.add(Box.createVerticalStrut(8));
            center.add(status);
            root.add(center, BorderLayout.CENTER);

            JPanel buttons = new JPanel();
            buttons.setOpaque(false);
            buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
            update.setFont(update.getFont().deriveFont(Font.BOLD, 12f));
            release.setFont(release.getFont().deriveFont(Font.PLAIN, 11f));
            ignore.setFont(ignore.getFont().deriveFont(Font.PLAIN, 11f));
            update.addActionListener(e -> start());
            ignore.addActionListener(e -> {
                if (!running) ignoreAndStart();
            });
            release.addActionListener(e -> openReleasePage());
            buttons.add(release);
            buttons.add(Box.createHorizontalGlue());
            buttons.add(ignore);
            buttons.add(Box.createHorizontalStrut(8));
            buttons.add(update);
            root.add(buttons, BorderLayout.SOUTH);

            frame.getRootPane().setDefaultButton(update);
            return root;
        }

        private String describeTarget() {
            return context.loader() + " " + context.minecraftVersion()
                    + " / " + UpdaterInstall.human(context.assetSize());
        }

        private void start() {
            if (running) return;
            running = true;
            update.setEnabled(false);
            ignore.setEnabled(false);
            frame.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
            status.setText("準備中…");
            new Thread(this::runUpdate, "GuildQuests-UpdaterWindow").start();
        }

        private void runUpdate() {
            try {
                Path staged = UpdaterInstall.download(context, this::setProgress);
                if (!staged.getFileName().toString().equals(context.fileName())) {
                    throw new IOException("ファイル名が一致しません");
                }
                setStatus("インストールを準備しています…");
                UpdaterInstall.cleanUpdaterCopies(context.stagingDir());
                Path plan = UpdaterInstall.scheduleInstall(context, staged, context.selfJar());

                // Ask the game to shut down; the helper swaps the JAR once the process is gone.
                Path marker = UpdaterInstall.restartMarker(context);
                java.nio.file.Files.deleteIfExists(marker);
                java.nio.file.Files.createFile(marker);
                setStatus("Minecraft を終了しています…");
                UpdaterInstall.waitForProcess(context.parentPid(), 90_000);

                setStatus("JAR を差し替えています…");
                if (!waitForInstall(plan, 120_000)) {
                    throw new IOException("JAR の差し替えに失敗しました");
                }
                SwingUtilities.invokeLater(this::finishInstalled);
            } catch (Exception e) {
                String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                SwingUtilities.invokeLater(() -> fail(message));
            }
        }

        private boolean waitForInstall(Path plan, long timeoutMillis) {
            long deadline = System.currentTimeMillis() + timeoutMillis;
            while (System.currentTimeMillis() < deadline) {
                if (!java.nio.file.Files.exists(plan)) return true;
                try {
                    Thread.sleep(250);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return false;
        }

        private void ignoreAndStart() {
            try {
                java.nio.file.Files.deleteIfExists(UpdaterInstall.restartMarker(context));
            } catch (Exception ignored) {
            }
            frame.dispose();
            System.exit(0);
        }

        private void setProgress(int percent, String message) {
            SwingUtilities.invokeLater(() -> {
                progress.setValue(percent);
                status.setText(message);
            });
        }

        private void setStatus(String message) {
            SwingUtilities.invokeLater(() -> status.setText(message));
        }

        private void finishInstalled() {
            running = false;
            frame.setCursor(Cursor.getDefaultCursor());
            progress.setValue(100);
            status.setText("更新が完了しました。Minecraft を起動し直してください。");
            status.setForeground(ACCENT);
            for (var listener : update.getActionListeners()) update.removeActionListener(listener);
            update.setText("閉じる");
            update.setEnabled(true);
            update.addActionListener(e -> frame.dispose());
            ignore.setVisible(false);
        }

        private void fail(String message) {
            running = false;
            frame.setCursor(Cursor.getDefaultCursor());
            status.setText("失敗：" + message);
            status.setForeground(new Color(0xB0, 0x2A, 0x2A));
            update.setEnabled(true);
            ignore.setEnabled(true);
        }

        private void openReleasePage() {
            try {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    Desktop.getDesktop().browse(URI.create(context.releaseUrl()));
                    return;
                }
            } catch (Exception ignored) {
            }
            JOptionPane.showMessageDialog(frame, context.releaseUrl(), "リリースページ", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private static void showError(String message) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
        }
        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(null, message, "更新エラー", JOptionPane.ERROR_MESSAGE));
    }
}
