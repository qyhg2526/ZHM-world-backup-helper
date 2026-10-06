package com.dsh.worldbackup.compress;

import com.dsh.worldbackup.config.PluginSettings;
import com.dsh.worldbackup.util.BackupException;
import com.dsh.worldbackup.util.CancelledException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * 调用系统 7-Zip（7z / 7za / 7zz / 7zr / p7zip）执行压缩。
 * 支持固实压缩，压缩率通常优于内置引擎，并可直接解析 7-Zip 输出的百分比进度。
 */
public final class SevenZipCliCompressor implements Compressor {

    private final Path executable;
    private final Path workingDir;
    private final PluginSettings settings;
    private final Logger log;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile Process process;
    private volatile boolean timedOut;

    public SevenZipCliCompressor(Path executable, Path workingDir, PluginSettings settings, Logger log) {
        this.executable = executable;
        this.workingDir = workingDir;
        this.settings = settings;
        this.log = log;
    }

    @Override
    public String displayName() {
        return "7z 命令行 (" + executable.getFileName() + ")";
    }

    @Override
    public String extension() {
        return ".7z";
    }

    @Override
    public CompressionResult compress(SourceSet set, Path archive, ProgressCallback callback) throws Exception {
        Files.createDirectories(archive.getParent());
        Files.deleteIfExists(archive);

        List<String> command = buildCommand(set, archive);
        if (log != null) {
            log.fine("执行压缩命令: " + String.join(" ", command));
        }

        long start = System.currentTimeMillis();
        Process proc = new ProcessBuilder(command).directory(workingDir.toFile()).start();
        this.process = proc;

        StringBuilder errorOutput = new StringBuilder();
        Thread stderrThread = new Thread(() -> drainErrors(proc, errorOutput), "ZHMBackup-7z-log");
        stderrThread.setDaemon(true);
        stderrThread.start();

        Thread watchdog = null;
        if (settings.timeoutMinutes() > 0) {
            watchdog = new Thread(() -> {
                try {
                    if (!proc.waitFor(settings.timeoutMinutes(), TimeUnit.MINUTES)) {
                        timedOut = true;
                        proc.destroyForcibly();
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }, "ZHMBackup-7z-watchdog");
            watchdog.setDaemon(true);
            watchdog.start();
        }

        long totalBytes = set.totalBytes();
        try (Reader reader = new InputStreamReader(proc.getInputStream(), outputCharset())) {
            int ch;
            StringBuilder digits = new StringBuilder(4);
            while ((ch = reader.read()) != -1) {
                if (cancelled.get()) {
                    proc.destroy();
                    throw new CancelledException("备份已被管理员取消");
                }
                if (ch >= '0' && ch <= '9') {
                    if (digits.length() < 3) {
                        digits.append((char) ch);
                    }
                } else {
                    if (ch == '%' && digits.length() > 0) {
                        try {
                            int percent = Integer.parseInt(digits.toString());
                            if (percent >= 0 && percent <= 100) {
                                callback.onProgress((long) (totalBytes * (percent / 100.0)), totalBytes, percent / 100.0);
                            }
                        } catch (NumberFormatException ignored) {
                            // 忽略无法解析的片段
                        }
                    }
                    digits.setLength(0);
                }
            }
        }

        int exitCode = proc.waitFor();
        if (watchdog != null) {
            watchdog.interrupt();
        }

        if (cancelled.get()) {
            Files.deleteIfExists(archive);
            throw new CancelledException("备份已被管理员取消");
        }
        if (timedOut) {
            Files.deleteIfExists(archive);
            throw new BackupException("压缩超时（超过 " + settings.timeoutMinutes() + " 分钟），已中止");
        }

        String errors = errorOutput.toString().trim();
        // 7-Zip 退出码：0=成功，1=警告（部分文件被占用 / 无法打开等），>=2 为错误。
        // 警告往往意味着有文件被跳过，因此默认视为失败，避免生成不完整的备份。
        if (exitCode == 1 && settings.failOnWarning()) {
            Files.deleteIfExists(archive);
            if (errors.isEmpty()) {
                errors = "7-Zip 报告了未知警告";
            }
            throw new BackupException("7-Zip 未能完整备份（有文件被跳过）：" + tail(errors)
                    + " | 若确认可以忽略，可将 compression.fail-on-warning 设为 false");
        }
        if (exitCode > 1) {
            Files.deleteIfExists(archive);
            throw new BackupException("7-Zip 压缩失败（退出码 " + exitCode + "）"
                    + (errors.isEmpty() ? "" : "：" + tail(errors)));
        }
        if (exitCode == 1 && log != null) {
            log.warning("7-Zip 返回警告（可能有文件被占用），备份仍已生成：" + tail(errors));
        }
        if (!Files.isRegularFile(archive)) {
            throw new BackupException("7-Zip 未生成压缩包，请检查磁盘空间与权限");
        }

        long archiveBytes = Files.size(archive);
        long elapsed = System.currentTimeMillis() - start;
        callback.onProgress(totalBytes, totalBytes, 1.0);
        return new CompressionResult(archive, archiveBytes, totalBytes, set.totalFiles(), elapsed, displayName());
    }

    /**
     * 7-Zip 的控制台输出使用系统本地编码（Windows 中文为 GBK），
     * 而 Java 18+ 的默认字符集是 UTF-8，因此需要显式使用 native.encoding。
     */
    private static Charset outputCharset() {
        String nativeEncoding = System.getProperty("native.encoding");
        if (nativeEncoding != null && !nativeEncoding.isBlank()) {
            try {
                return Charset.forName(nativeEncoding);
            } catch (Exception ignored) {
                // 回退到默认字符集
            }
        }
        return Charset.defaultCharset();
    }

    private List<String> buildCommand(SourceSet set, Path archive) {
        List<String> command = new ArrayList<>();
        command.add(executable.toAbsolutePath().toString());
        command.add("a");
        command.add("-t7z");
        command.add("-mx=" + settings.level());
        command.add("-mmt=" + (settings.threads() > 0 ? String.valueOf(settings.threads()) : "on"));
        command.add("-ms=" + (settings.solid() ? "on" : "off"));
        if (settings.compressLockedFiles()) {
            // -ssw：允许压缩被其他程序（运行中的服务端）占用的文件。
            // Windows 上缺少该参数会导致区块文件被跳过，备份不完整。
            command.add("-ssw");
        }
        command.add("-y");
        // 常规输出静默，错误走 stderr，百分比进度走 stdout，便于解析
        command.add("-bso0");
        command.add("-bse2");
        command.add("-bsp1");
        for (String extra : settings.extraArgs()) {
            if (extra != null && !extra.isBlank()) {
                command.add(extra);
            }
        }
        for (String pattern : settings.exclude()) {
            if (pattern != null && !pattern.isBlank()) {
                command.add("-xr!" + pattern);
            }
        }
        command.add(archive.toAbsolutePath().toString());
        for (SourceEntry entry : set.entries()) {
            command.add(sourceArgument(entry.path()));
        }
        return command;
    }

    /** 尽量使用相对服务器根目录的路径，使压缩包内保留 world/ 顶层结构。 */
    private String sourceArgument(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        try {
            if (absolute.startsWith(workingDir)) {
                return workingDir.relativize(absolute).toString().replace('\\', '/');
            }
        } catch (IllegalArgumentException ignored) {
            // 跨盘符时直接使用绝对路径
        }
        return absolute.toString();
    }

    private void drainErrors(Process proc, StringBuilder sink) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(proc.getErrorStream(), outputCharset()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (sink.length() < 8000) {
                    sink.append(line).append('\n');
                }
            }
        } catch (IOException ignored) {
            // 进程结束时流会关闭
        }
    }

    private static String tail(String text) {
        String trimmed = text.strip();
        return trimmed.length() <= 600 ? trimmed : trimmed.substring(trimmed.length() - 600);
    }

    @Override
    public void cancel() {
        cancelled.set(true);
        Process proc = this.process;
        if (proc != null && proc.isAlive()) {
            proc.destroy();
        }
    }
}
