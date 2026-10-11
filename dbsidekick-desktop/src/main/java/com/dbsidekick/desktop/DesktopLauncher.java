package com.dbsidekick.desktop;

import com.dbsidekick.DBSidekickApplication;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Image;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;
import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import me.friwi.jcefmaven.CefAppBuilder;
import me.friwi.jcefmaven.MavenCefAppHandlerAdapter;
import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.browser.CefBrowser;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * JavaFX 外壳：内嵌 Spring Boot，用 JCEF（Chromium）加载前端。
 * JCEF 在 {@link #init()} 里、任何 Stage 出现之前完成。此时 JavaFX 应用线程没有窗口可等，
 * {@code CefApp.getInstance} 内部的 {@code SwingUtilities.invokeAndWait} 才不会和已显示的舞台死锁。
 */
public class DesktopLauncher extends Application {

    private ConfigurableApplicationContext springContext;
    private int port;
    private CefApp cefApp;
    private CefClient cefClient;
    private CefBrowser cefBrowser;
    private Component browserUI;
    private JFrame frame;
    private StartupSplash splash;
    private volatile boolean closing;
    private volatile Throwable startupError;
    /** spring：进程内重试不了；jcef：下载或初始化可以再来一次。 */
    private volatile String failedStage = "spring";

    @Override
    public void init() {
        System.setProperty("java.awt.headless", "false");
        boolean firstLaunch = !nativesCached();
        try {
            SwingUtilities.invokeAndWait(() -> {
                splash = new StartupSplash(firstLaunch);
                splash.show();
            });
        } catch (Exception ex) {
            System.err.println("[Sidekick] 启动窗口未能提前显示: " + ex.getMessage());
        }
        try {
            if (Boolean.parseBoolean(System.getProperty("dbsidekick.force.boot.fail", "false"))) {
                throw new IllegalStateException("模拟启动失败（-Ddbsidekick.force.boot.fail=true）");
            }
            System.out.println(firstLaunch
                    ? "[Sidekick] 正在首次初始化，请稍候..."
                    : "[Sidekick] 正在启动...");
            springContext = new SpringApplicationBuilder(DBSidekickApplication.class)
                    .headless(false)
                    .run("--server.port=0");
            port = ((ServletWebServerApplicationContext) springContext).getWebServer().getPort();
            System.out.println("[Sidekick] desktop Spring Boot ready on port " + port);

            // 不用 commonPool：CEF 初始化不能放在 ForkJoin 的守护线程上。
            CompletableFuture<Void> jcefReady = new CompletableFuture<>();
            Thread jcefThread = new Thread(() -> {
                try {
                    initJcef();
                    jcefReady.complete(null);
                } catch (Exception ex) {
                    jcefReady.completeExceptionally(ex);
                }
            }, "dbsidekick-jcef-init");
            jcefThread.setDaemon(false);
            jcefThread.start();
            // init() 跑在 JavaFX-Launcher，不是应用线程，这里阻塞不会卡住 FX 事件循环。
            jcefReady.get(120, TimeUnit.SECONDS);
            System.out.println("[Sidekick] JCEF ready");
        } catch (Exception ex) {
            startupError = unwrap(ex);
            failedStage = springContext == null ? "spring" : "jcef";
            writeErrorLog(startupError);
            System.err.println("[Sidekick] startup failed: " + startupError.getMessage());
        }
    }

    private void initJcef() throws Exception {
        System.out.println("[Sidekick] JCEF build on " + Thread.currentThread().getName());
        Path home = Paths.get(System.getProperty("user.home"), ".dbsidekick");
        File installDir = home.resolve("jcef-bundle").toFile();
        File cacheRoot = home.resolve("cef-cache").toFile();
        File cache = new File(cacheRoot, "cache");
        Files.createDirectories(home.resolve("logs"));
        installDir.mkdirs();
        cache.mkdirs();

        CefAppBuilder builder = new CefAppBuilder();
        builder.setInstallDir(installDir);
        builder.getCefSettings().windowless_rendering_enabled = false;
        // CEF 120+ 设置了 cache_path 就必须同时给 root_cache_path。
        builder.getCefSettings().root_cache_path = cacheRoot.getAbsolutePath();
        builder.getCefSettings().cache_path = cache.getAbsolutePath();
        builder.getCefSettings().log_file = home.resolve("logs").resolve("jcef.log").toString();
        builder.setAppHandler(new MavenCefAppHandlerAdapter() {
            @Override
            public void stateHasChanged(CefApp.CefAppState state) {
                if (state == CefApp.CefAppState.TERMINATED && closing) {
                    System.exit(0);
                }
            }
        });
        builder.setProgressHandler((state, percent) -> {
            System.out.println("[Sidekick] JCEF " + state + " " + percent);
            if (splash != null) {
                splash.onJcefProgress(state, percent);
            }
        });
        if (!nativesCached()) {
            if (splash != null) {
                splash.setDeterminate(50, "正在检查运行环境...");
            }
        } else if (splash != null) {
            splash.setDeterminate(95, "正在初始化浏览器...");
        }
        cefApp = builder.build();
    }

    /** install.lock 和 build_meta.json 都在，说明运行组件已经缓存，不用再下载。 */
    private static boolean nativesCached() {
        Path install = Paths.get(System.getProperty("user.home"), ".dbsidekick", "jcef-bundle");
        return Files.exists(install.resolve("install.lock"))
                && Files.exists(install.resolve("build_meta.json"));
    }

    @Override
    public void start(Stage primaryStage) {
        if (startupError != null) {
            showStartupFailure();
            return;
        }
        // JCEF 窗口模式用的是重量级 Canvas，SwingNode 嵌不进去，画面会是空白。
        // 主窗口用独立的 Swing JFrame，等启动小窗淡出后再创建。
        Platform.setImplicitExit(false);
        if (splash != null) {
            splash.finishThen(this::showBrowserFrame);
        } else {
            SwingUtilities.invokeLater(this::showBrowserFrame);
        }
    }

    private void showBrowserFrame() {
        frame = new JFrame("DBSidekick");
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                closeDesktop();
            }
        });
        applyWindowIcon(frame);
        frame.setSize(1400, 900);
        frame.setLocationRelativeTo(null);
        // 浏览器画布要挂在已经创建好的主窗口上，不能提前在启动小窗期间创建。
        cefClient = cefApp.createClient();
        cefBrowser = cefClient.createBrowser(
                "http://127.0.0.1:" + port + "/",
                false,
                false);
        browserUI = cefBrowser.getUIComponent();
        frame.getContentPane().add(browserUI, BorderLayout.CENTER);
        frame.setVisible(true);
        frame.toFront();
    }

    private void applyWindowIcon(JFrame frame) {
        try (var in = DesktopLauncher.class.getResourceAsStream("/icons/dbsidekick.png")) {
            if (in == null) {
                return;
            }
            Image icon = ImageIO.read(in);
            if (icon != null) {
                frame.setIconImage(icon);
            }
        } catch (Exception ignored) {
            // 图标缺失时仍用系统默认图标
        }
    }

    private void closeDesktop() {
        if (closing) {
            return;
        }
        closing = true;
        Thread closer = new Thread(() -> {
            try {
                if (cefBrowser != null) {
                    cefBrowser.close(true);
                }
                if (cefClient != null) {
                    cefClient.dispose();
                }
                if (cefApp != null) {
                    cefApp.dispose();
                }
            } catch (Exception ignored) {
                // CEF 关闭失败时仍继续退出，避免僵尸进程
            }
            shutdownSpring();
            try {
                Thread.sleep(800);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            System.exit(0);
        }, "dbsidekick-desktop-shutdown");
        closer.setDaemon(false);
        closer.start();
    }

    private void showStartupFailure() {
        String message = friendlyStartupError(startupError);
        boolean canRetry = "jcef".equals(failedStage);
        if (splash != null) {
            splash.showFailure(message, canRetry, this::retryJcef);
            return;
        }
        System.err.println("[Sidekick] " + message);
        shutdownSpring();
        Platform.exit();
        System.exit(1);
    }

    private void retryJcef() {
        Thread retry = new Thread(() -> {
            try {
                startupError = null;
                initJcef();
                System.out.println("[Sidekick] JCEF ready");
                if (splash != null) {
                    splash.finishThen(this::showBrowserFrame);
                } else {
                    SwingUtilities.invokeLater(this::showBrowserFrame);
                }
            } catch (Exception ex) {
                startupError = unwrap(ex);
                failedStage = "jcef";
                writeErrorLog(startupError);
                System.err.println("[Sidekick] startup failed: " + startupError.getMessage());
                showStartupFailure();
            }
        }, "dbsidekick-jcef-retry");
        retry.setDaemon(false);
        retry.start();
    }

    static String friendlyStartupError(Throwable error) {
        if (error instanceof TimeoutException) {
            String timeoutMessage = error.getMessage();
            if (timeoutMessage == null || timeoutMessage.isBlank()) {
                return "启动超时，请检查网络或查看日志";
            }
        }
        String raw = error == null || error.getMessage() == null ? "" : error.getMessage();
        String lower = raw.toLowerCase();
        if (lower.contains("download")
                || lower.contains("timeout")
                || lower.contains("timed out")
                || lower.contains("cef")
                || lower.contains("jcef")) {
            return "网络连接失败，请检查网络或代理后重试";
        }
        if (lower.contains("address already in use")
                || lower.contains("bindexception")
                || raw.contains("端口")) {
            return "端口被占用";
        }
        if (raw.isBlank()) {
            return "启动失败，请查看日志";
        }
        return raw;
    }

    private Path writeErrorLog(Throwable e) {
        try {
            Path dir = Paths.get(System.getProperty("user.home"), ".dbsidekick", "logs");
            Files.createDirectories(dir);
            Path logPath = dir.resolve("jcef-error.log");
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            String body = LocalDateTime.now() + "\n" + sw + "\n";
            Files.writeString(logPath, body, StandardCharsets.UTF_8);
            Files.writeString(dir.resolve("startup-error.log"), body, StandardCharsets.UTF_8);
            return logPath;
        } catch (Exception io) {
            io.printStackTrace();
            return null;
        }
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof java.util.concurrent.ExecutionException
                || current instanceof java.util.concurrent.CompletionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    @Override
    public void stop() {
        shutdownSpring();
    }

    private void shutdownSpring() {
        if (springContext != null) {
            try {
                springContext.close();
            } catch (Exception ignored) {
                // ignore
            }
            springContext = null;
        }
    }

    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "false");
        launch(args);
    }
}
