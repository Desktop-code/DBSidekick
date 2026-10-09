package com.dbsidekick.controller;

import com.dbsidekick.config.SqliteInitializer;
import java.awt.Desktop;
import java.awt.FileDialog;
import java.awt.Frame;
import java.awt.Window;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 系统级辅助接口：日志、数据目录、备份与恢复。
 */
@RestController
@RequestMapping("/api/system")
public class SystemController {

    private final SqliteInitializer sqliteInitializer;

    public SystemController(SqliteInitializer sqliteInitializer) {
        this.sqliteInitializer = sqliteInitializer;
    }

    static Path logDir() {
        return Paths.get(System.getProperty("user.home"), ".dbsidekick", "logs");
    }

    static Path logFile() {
        return logDir().resolve("dbsidekick.log");
    }

    @GetMapping("/log-path")
    public Map<String, Object> logPath() {
        Path file = logFile();
        Path dir = logDir();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("logFile", file.toAbsolutePath().toString());
        body.put("logDir", dir.toAbsolutePath().toString());
        body.put("exists", Files.exists(file));
        return body;
    }

    @GetMapping("/data-path")
    public Map<String, Object> dataPath() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dbPath", SqliteInitializer.DB_PATH.toAbsolutePath().toString());
        body.put("dataDir", sqliteInitializer.dataDir().toAbsolutePath().toString());
        body.put("dbSize", sqliteInitializer.dbSize());
        body.put("logPath", logFile().toAbsolutePath().toString());
        return body;
    }

    @PostMapping("/backup")
    public Map<String, Object> backup(@RequestBody Map<String, String> body) {
        String target = body == null ? null : body.get("targetPath");
        if (!StringUtils.hasText(target)) {
            throw new IllegalArgumentException("targetPath 不能为空");
        }
        try {
            Path dest = Path.of(target.trim());
            sqliteInitializer.backupTo(dest);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success", true);
            resp.put("path", dest.toAbsolutePath().toString());
            resp.put("size", Files.size(dest));
            return resp;
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    ex.getMessage() == null ? "备份失败" : ex.getMessage(), ex);
        }
    }

    @PostMapping("/restore")
    public Map<String, Object> restore(@RequestBody Map<String, String> body) {
        String source = body == null ? null : body.get("sourcePath");
        if (!StringUtils.hasText(source)) {
            throw new IllegalArgumentException("sourcePath 不能为空");
        }
        try {
            sqliteInitializer.restoreFrom(Path.of(source.trim()));
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success", true);
            resp.put("message", "恢复成功，请重启应用");
            return resp;
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    ex.getMessage() == null ? "恢复失败" : ex.getMessage(), ex);
        }
    }

    @PostMapping("/reset")
    public Map<String, Object> reset() {
        try {
            sqliteInitializer.resetDatabase();
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success", true);
            resp.put("message", "已重置，请重启应用");
            return resp;
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    ex.getMessage() == null ? "重置失败" : ex.getMessage(), ex);
        }
    }

    @PostMapping("/open-data-dir")
    public Map<String, Object> openDataDir() {
        return openDir(sqliteInitializer.dataDir());
    }

    @PostMapping("/open-log-dir")
    public Map<String, Object> openLogDir() {
        return openDir(logDir());
    }

    @PostMapping("/choose-file")
    public Map<String, Object> chooseFile(@RequestBody Map<String, String> body) {
        String title = body == null ? null : body.get("title");
        String mode = body == null ? "open" : body.getOrDefault("mode", "open");
        String defaultName = body == null ? null : body.get("defaultName");
        String extensions = body == null ? null : body.get("extensions");
        String initialDir = body == null ? null : body.get("initialDir");
        if (!StringUtils.hasText(title)) {
            title = "save".equalsIgnoreCase(mode) ? "保存备份" : "选择备份文件";
        }
        if (StringUtils.hasText(extensions)) {
            return chooseWithFilter(title, mode, extensions, initialDir);
        }
        try {
            System.setProperty("java.awt.headless", "false");
            int awtMode = "save".equalsIgnoreCase(mode) ? FileDialog.SAVE : FileDialog.LOAD;
            AtomicReference<String> selected = new AtomicReference<>();
            String dialogTitle = title;
            SwingUtilities.invokeAndWait(() -> {
                FileDialog dialog = new FileDialog((Frame) null, dialogTitle, awtMode);
                dialog.setFilenameFilter((dir, name) -> name.toLowerCase().endsWith(".db"));
                if (StringUtils.hasText(defaultName)) {
                    dialog.setFile(defaultName.trim());
                }
                dialog.setVisible(true);
                if (dialog.getFile() != null && dialog.getDirectory() != null) {
                    selected.set(new File(dialog.getDirectory(), dialog.getFile()).getAbsolutePath());
                }
            });
            Map<String, Object> resp = new LinkedHashMap<>();
            if (!StringUtils.hasText(selected.get())) {
                resp.put("canceled", true);
                return resp;
            }
            resp.put("path", selected.get());
            resp.put("canceled", false);
            return resp;
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    ex.getMessage() == null ? "无法打开文件选择器" : ex.getMessage(), ex);
        }
    }

    private Map<String, Object> chooseWithFilter(String title, String mode, String extensions, String initialDir) {
        try {
            System.setProperty("java.awt.headless", "false");
            List<String> extList = new ArrayList<>();
            for (String part : extensions.split(",")) {
                String ext = part == null ? "" : part.trim().toLowerCase();
                if (ext.startsWith(".")) {
                    ext = ext.substring(1);
                }
                if (!ext.isEmpty()) {
                    extList.add(ext);
                }
            }
            AtomicReference<String> selected = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                JFileChooser chooser = new JFileChooser();
                chooser.setDialogTitle(title);
                chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
                if (!extList.isEmpty()) {
                    chooser.setAcceptAllFileFilterUsed(false);
                    chooser.setFileFilter(new FileNameExtensionFilter(
                            String.join(", ", extList), extList.toArray(String[]::new)));
                }
                File start = resolveStartDir(initialDir);
                if (start != null) {
                    chooser.setCurrentDirectory(start);
                }
                Window parent = null;
                for (Window window : Window.getWindows()) {
                    if (window.isShowing()) {
                        parent = window;
                    }
                }
                int result = "save".equalsIgnoreCase(mode)
                        ? chooser.showSaveDialog(parent)
                        : chooser.showOpenDialog(parent);
                if (result == JFileChooser.APPROVE_OPTION && chooser.getSelectedFile() != null) {
                    selected.set(chooser.getSelectedFile().getAbsolutePath());
                }
            });
            Map<String, Object> resp = new LinkedHashMap<>();
            if (!StringUtils.hasText(selected.get())) {
                resp.put("canceled", true);
                return resp;
            }
            resp.put("path", selected.get());
            resp.put("canceled", false);
            return resp;
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    ex.getMessage() == null ? "无法打开文件选择器" : ex.getMessage(), ex);
        }
    }

    private static File resolveStartDir(String initialDir) {
        if (StringUtils.hasText(initialDir)) {
            File file = new File(initialDir.trim());
            if (file.isDirectory()) {
                return file;
            }
            if (file.getParentFile() != null && file.getParentFile().isDirectory()) {
                return file.getParentFile();
            }
        }
        String home = System.getProperty("user.home");
        return StringUtils.hasText(home) ? new File(home) : null;
    }

    @PostMapping("/choose-dir")
    public Map<String, Object> chooseDir(@RequestBody(required = false) Map<String, String> body) {
        String title = body == null ? null : body.get("title");
        if (!StringUtils.hasText(title)) {
            title = "选择向量模型目录";
        }
        try {
            System.setProperty("java.awt.headless", "false");
            AtomicReference<String> selected = new AtomicReference<>();
            String dialogTitle = title;
            SwingUtilities.invokeAndWait(() -> {
                JFileChooser chooser = new JFileChooser();
                chooser.setDialogTitle(dialogTitle);
                chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                chooser.setAcceptAllFileFilterUsed(false);
                String home = System.getProperty("user.home");
                if (StringUtils.hasText(home)) {
                    chooser.setCurrentDirectory(new File(home));
                }
                Window parent = null;
                for (Window window : Window.getWindows()) {
                    if (window.isShowing()) {
                        parent = window;
                    }
                }
                if (chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION && chooser.getSelectedFile() != null) {
                    selected.set(chooser.getSelectedFile().getAbsolutePath());
                }
            });
            Map<String, Object> resp = new LinkedHashMap<>();
            if (!StringUtils.hasText(selected.get())) {
                resp.put("canceled", true);
                return resp;
            }
            resp.put("path", selected.get());
            resp.put("canceled", false);
            return resp;
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    ex.getMessage() == null ? "无法打开目录选择器" : ex.getMessage(), ex);
        }
    }

    @PostMapping("/check-model-dir")
    public Map<String, Object> checkModelDir(@RequestBody Map<String, String> body) {
        String raw = body == null ? null : body.get("path");
        Map<String, Object> resp = new LinkedHashMap<>();
        if (!StringUtils.hasText(raw)) {
            resp.put("ok", false);
            resp.put("message", "请选择向量模型目录");
            return resp;
        }
        Path dir = Path.of(raw.trim());
        boolean onnx = Files.isRegularFile(dir.resolve("model.onnx"));
        boolean tokenizer = Files.isRegularFile(dir.resolve("tokenizer.json"));
        resp.put("ok", onnx && tokenizer);
        if (onnx && tokenizer) {
            resp.put("message", "模型文件齐全");
        } else if (!Files.isDirectory(dir)) {
            resp.put("message", "目录不存在");
        } else {
            resp.put("message", "目录中需要同时有 model.onnx 和 tokenizer.json");
        }
        return resp;
    }

    private Map<String, Object> openDir(Path dirPath) {
        try {
            Files.createDirectories(dirPath);
            File dir = dirPath.toFile();
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(dir);
            } else {
                String os = System.getProperty("os.name", "").toLowerCase();
                if (os.contains("win")) {
                    new ProcessBuilder("explorer.exe", dir.getAbsolutePath()).start();
                } else {
                    throw new IllegalStateException("当前环境不支持打开目录");
                }
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", true);
            body.put("success", true);
            body.put("path", dir.getAbsolutePath());
            return body;
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage(), ex);
        }
    }
}
