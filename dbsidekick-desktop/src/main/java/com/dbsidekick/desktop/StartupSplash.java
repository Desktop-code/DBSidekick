package com.dbsidekick.desktop;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import me.friwi.jcefmaven.EnumProgress;

/**
 * 启动小窗。和后面的浏览器主窗口不是同一个 frame。
 * 动画出问题只记一下，不能挡住后面打开主窗口。
 */
final class StartupSplash {

    static final String FIRST_LAUNCH_TIP =
            "首次启动需要下载运行组件，约 1-2 分钟，请耐心等待。下载完成后会缓存在本地，后续启动只需几秒。";

    private static final int WIDTH = 480;
    private static final int HEIGHT = 320;
    private static final Color TEXT = new Color(0x30, 0x31, 0x33);
    private static final Color MUTED = new Color(0x90, 0x93, 0x99);
    private static final Color BAR_TRACK = new Color(0xEB, 0xEE, 0xF5);
    private static final Color BAR_FILL = new Color(0x40, 0x9E, 0xFF);
    private static final Color BAR_FAIL = new Color(0xF5, 0x6C, 0x6C);
    private static final Color TIP_BG = new Color(0xEC, 0xF5, 0xFF);

    private final boolean firstLaunch;
    private final JFrame frame = new JFrame();
    private final JLabel status = new JLabel("正在启动…", SwingConstants.CENTER);
    private final ThinBar bar = new ThinBar();
    private final JPanel actions = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 0));
    private Timer flow;
    private volatile boolean finishing;

    StartupSplash(boolean firstLaunch) {
        this.firstLaunch = firstLaunch;
    }

    void show() {
        try {
            Font base = new Font("Microsoft YaHei", Font.PLAIN, 13);
            frame.setUndecorated(true);
            frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            frame.setSize(WIDTH, HEIGHT);
            frame.setResizable(false);
            frame.setAlwaysOnTop(true);

            JPanel root = new JPanel(new BorderLayout());
            root.setBackground(Color.WHITE);
            root.setBorder(BorderFactory.createLineBorder(new Color(0xDC, 0xDF, 0xE6)));

            JPanel top = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 6));
            top.setOpaque(false);
            top.add(closeButton(base));
            root.add(top, BorderLayout.NORTH);

            JPanel center = new JPanel();
            center.setOpaque(false);
            center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
            center.add(Box.createVerticalStrut(8));
            center.add(logo());
            center.add(Box.createVerticalStrut(12));
            center.add(centered(title(base)));
            center.add(Box.createVerticalStrut(6));
            center.add(centered(subtitle(base)));
            center.add(Box.createVerticalStrut(22));
            center.add(centered(bar));
            center.add(Box.createVerticalStrut(12));
            status.setFont(base);
            status.setForeground(new Color(0x60, 0x62, 0x66));
            center.add(centered(status));
            center.add(Box.createVerticalStrut(14));
            actions.setOpaque(false);
            actions.setVisible(false);
            center.add(actions);
            root.add(center, BorderLayout.CENTER);

            JPanel bottom = new JPanel();
            bottom.setOpaque(false);
            bottom.setLayout(new BoxLayout(bottom, BoxLayout.Y_AXIS));
            if (firstLaunch) {
                bottom.add(tip(base));
            }
            JLabel version = new JLabel("v0.0.1  ");
            version.setFont(base.deriveFont(11f));
            version.setForeground(MUTED);
            version.setAlignmentX(1f);
            version.setHorizontalAlignment(SwingConstants.RIGHT);
            JPanel versionRow = new JPanel(new BorderLayout());
            versionRow.setOpaque(false);
            versionRow.setBorder(BorderFactory.createEmptyBorder(4, 0, 8, 8));
            versionRow.add(version, BorderLayout.EAST);
            bottom.add(versionRow);
            root.add(bottom, BorderLayout.SOUTH);

            frame.setContentPane(root);
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
            setIndeterminate("正在启动服务...");
        } catch (Exception ex) {
            System.err.println("[Sidekick] Splash 显示失败，继续启动: " + ex.getMessage());
        }
    }

    void setIndeterminate(String text) {
        runOnEdt(() -> {
            bar.setPercent(-1);
            status.setText(text);
            ensureFlow();
        });
    }

    void setDeterminate(int percent, String text) {
        runOnEdt(() -> {
            stopFlow();
            bar.setPercent(Math.max(0, Math.min(100, percent)));
            status.setText(text);
        });
    }

    /** JCEF 下载回调。已缓存时忽略下载阶段，进度停在初始化。 */
    void onJcefProgress(EnumProgress state, float percent) {
        if (state == null) {
            return;
        }
        try {
            if (!firstLaunch && state != EnumProgress.INITIALIZING && state != EnumProgress.INITIALIZED) {
                return;
            }
            switch (state) {
                case LOCATING -> setDeterminate(50, "正在检查运行环境...");
                case DOWNLOADING -> {
                    int shown = percent < 0 ? 50 : 50 + Math.round(percent * 0.4f);
                    String suffix = percent < 0 ? "" : " " + Math.round(percent) + "%";
                    setDeterminate(shown, "首次启动：正在下载运行组件（约 126MB）..." + suffix);
                }
                case EXTRACTING -> setDeterminate(90, "正在解压组件...");
                case INSTALL -> setDeterminate(92, "正在安装组件...");
                case INITIALIZING -> setDeterminate(95, "正在初始化浏览器...");
                case INITIALIZED -> setDeterminate(98, "正在打开窗口...");
                default -> {
                }
            }
        } catch (Exception ex) {
            System.err.println("[Sidekick] Splash 进度更新失败: " + ex.getMessage());
        }
    }

    /** 启动失败：文字和进度条变红，并给出重试、日志、退出。 */
    void showFailure(String message, boolean canRetry, Runnable retry) {
        runOnEdt(() -> {
            try {
                stopFlow();
                bar.setColor(BAR_FAIL);
                bar.setPercent(100);
                status.setForeground(BAR_FAIL);
                status.setText(message == null || message.isBlank() ? "启动失败，请查看日志" : message);
                actions.removeAll();
                actions.add(actionButton("重试", () -> onRetry(canRetry, retry)));
                actions.add(actionButton("打开日志目录", this::openLogs));
                actions.add(actionButton("退出", () -> System.exit(1)));
                actions.setVisible(true);
                frame.revalidate();
                frame.repaint();
            } catch (Exception ex) {
                System.err.println("[Sidekick] Splash 失败提示未能显示: " + ex.getMessage());
            }
        });
    }

    private void onRetry(boolean canRetry, Runnable retry) {
        if (!canRetry || retry == null) {
            status.setText("服务启动失败，需要退出后重新打开。");
            return;
        }
        actions.setVisible(false);
        finishing = false;
        status.setForeground(new Color(0x60, 0x62, 0x66));
        bar.setColor(BAR_FILL);
        setIndeterminate("正在重试...");
        try {
            retry.run();
        } catch (Exception ex) {
            System.err.println("[Sidekick] 重试未能开始: " + ex.getMessage());
        }
    }

    private void openLogs() {
        try {
            Path dir = Paths.get(System.getProperty("user.home"), ".dbsidekick", "logs");
            Files.createDirectories(dir);
            Desktop.getDesktop().open(dir.toFile());
        } catch (Exception ex) {
            System.err.println("[Sidekick] 打开日志目录失败: " + ex.getMessage());
            status.setText("打不开日志目录，请手动打开用户目录下的 .dbsidekick\\logs");
        }
    }

    private JButton actionButton(String text, Runnable action) {
        JButton button = new JButton(text);
        button.setFocusPainted(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.addActionListener(event -> {
            try {
                action.run();
            } catch (Exception ex) {
                System.err.println("[Sidekick] Splash 按钮失败: " + ex.getMessage());
            }
        });
        return button;
    }

    /** 到 100% 显示就绪，停 200ms，150ms 淡出，再打开主窗口。 */
    void finishThen(Runnable after) {
        Runnable next = after == null ? () -> { } : after;
        runOnEdt(() -> {
            if (finishing) {
                return;
            }
            finishing = true;
            try {
                stopFlow();
                bar.setPercent(100);
                status.setText("就绪");
                Timer wait = new Timer(200, event -> {
                    ((Timer) event.getSource()).stop();
                    fadeOut(next);
                });
                wait.setRepeats(false);
                wait.start();
            } catch (Exception ex) {
                System.err.println("[Sidekick] Splash 收尾失败，直接打开主窗口: " + ex.getMessage());
                disposeQuietly();
                next.run();
            }
        });
    }

    void disposeQuietly() {
        try {
            stopFlow();
            if (frame.isDisplayable()) {
                frame.dispose();
            }
        } catch (Exception ignored) {
            // 关掉失败也不影响主窗口
        }
    }

    private void fadeOut(Runnable after) {
        try {
            frame.setAlwaysOnTop(false);
            Timer timer = new Timer(15, null);
            int[] step = {0};
            timer.addActionListener(event -> {
                try {
                    step[0]++;
                    frame.setOpacity(Math.max(0f, 1f - step[0] / 10f));
                    if (step[0] >= 10) {
                        timer.stop();
                        disposeQuietly();
                        after.run();
                    }
                } catch (Exception ex) {
                    timer.stop();
                    System.err.println("[Sidekick] Splash 淡出失败: " + ex.getMessage());
                    disposeQuietly();
                    after.run();
                }
            });
            timer.start();
        } catch (Exception ex) {
            System.err.println("[Sidekick] Splash 淡出失败: " + ex.getMessage());
            disposeQuietly();
            after.run();
        }
    }

    private void ensureFlow() {
        if (flow != null) {
            return;
        }
        flow = new Timer(30, event -> bar.stepFlow());
        flow.start();
    }

    private void stopFlow() {
        if (flow != null) {
            flow.stop();
            flow = null;
        }
    }

    private void runOnEdt(Runnable action) {
        try {
            if (SwingUtilities.isEventDispatchThread()) {
                action.run();
            } else {
                SwingUtilities.invokeLater(() -> {
                    try {
                        action.run();
                    } catch (Exception ex) {
                        System.err.println("[Sidekick] Splash 更新失败: " + ex.getMessage());
                    }
                });
            }
        } catch (Exception ex) {
            System.err.println("[Sidekick] Splash 更新失败: " + ex.getMessage());
        }
    }

    private JButton closeButton(Font base) {
        JButton close = new JButton("×");
        close.setFont(base.deriveFont(Font.PLAIN, 18f));
        close.setForeground(MUTED);
        close.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 4));
        close.setContentAreaFilled(false);
        close.setFocusPainted(false);
        close.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        close.addActionListener(event -> confirmExit());
        return close;
    }

    private void confirmExit() {
        if (finishing) {
            return;
        }
        int choice = JOptionPane.showOptionDialog(
                frame,
                "启动尚未完成，退出后需要重新启动。",
                "DBSidekick",
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.WARNING_MESSAGE,
                null,
                new Object[] {"取消", "退出"},
                "取消");
        if (choice == 1) {
            System.exit(0);
        }
    }

    private JLabel logo() {
        JLabel label = new JLabel();
        label.setAlignmentX(0.5f);
        label.setHorizontalAlignment(SwingConstants.CENTER);
        try (var in = StartupSplash.class.getResourceAsStream("/icons/dbsidekick.png")) {
            if (in != null) {
                Image image = ImageIO.read(in);
                if (image != null) {
                    label.setIcon(new javax.swing.ImageIcon(image.getScaledInstance(64, 64, Image.SCALE_SMOOTH)));
                }
            }
        } catch (Exception ignored) {
            // 没有图标就只显示文字
        }
        label.setPreferredSize(new Dimension(64, 64));
        label.setMaximumSize(new Dimension(64, 64));
        return label;
    }

    private static JLabel title(Font base) {
        JLabel label = new JLabel("DBSidekick", SwingConstants.CENTER);
        label.setFont(base.deriveFont(Font.BOLD, 22f));
        label.setForeground(TEXT);
        return label;
    }

    private static JLabel subtitle(Font base) {
        JLabel label = new JLabel("本地化多数据源 AI 查询助手", SwingConstants.CENTER);
        label.setFont(base.deriveFont(13f));
        label.setForeground(MUTED);
        return label;
    }

    private static JPanel tip(Font base) {
        JLabel label = new JLabel("<html><div style='width:420px'>💡 " + FIRST_LAUNCH_TIP + "</div></html>");
        label.setFont(base.deriveFont(12f));
        label.setForeground(new Color(0x40, 0x6E, 0xA5));
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(TIP_BG);
        panel.setBorder(BorderFactory.createEmptyBorder(8, 16, 8, 16));
        panel.add(label, BorderLayout.CENTER);
        return panel;
    }

    private static JPanel centered(java.awt.Component child) {
        JPanel wrap = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        wrap.setOpaque(false);
        wrap.add(child);
        return wrap;
    }

    /** 固定 320×4，不确定进度时画一段来回的色块。 */
    private static final class ThinBar extends JPanel {
        private int percent = -1;
        private int phase;
        private Color fill = BAR_FILL;

        ThinBar() {
            setPreferredSize(new Dimension(320, 4));
            setMinimumSize(new Dimension(320, 4));
            setMaximumSize(new Dimension(320, 4));
            setOpaque(false);
        }

        void setPercent(int percent) {
            this.percent = percent;
            repaint();
        }

        void setColor(Color fill) {
            this.fill = fill;
            repaint();
        }

        void stepFlow() {
            phase = (phase + 8) % 480;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            try {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int w = getWidth();
                int h = getHeight();
                g2.setColor(BAR_TRACK);
                g2.fillRect(0, 0, w, h);
                g2.setColor(fill);
                if (percent < 0) {
                    int piece = Math.max(48, w / 3);
                    int x = phase % (w + piece) - piece;
                    g2.fillRect(x, 0, piece, h);
                } else {
                    g2.fillRect(0, 0, Math.round(w * (percent / 100f)), h);
                }
                g2.dispose();
            } catch (Exception ignored) {
                // 绘制失败保持空白，不抛出去
            }
        }
    }
}
