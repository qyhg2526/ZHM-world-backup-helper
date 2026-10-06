package com.dsh.worldbackup.compress;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * 自动探测系统中的 7-Zip 可执行文件。
 */
public final class SevenZipLocator {

    private static final List<String> EXECUTABLE_NAMES = List.of(
            "7z", "7z.exe", "7za", "7za.exe", "7zz", "7zz.exe", "7zr", "7zr.exe");

    private static final List<String> COMMON_PATHS = List.of(
            "C:\\Program Files\\7-Zip\\7z.exe",
            "C:\\Program Files (x86)\\7-Zip\\7z.exe",
            "C:\\Program Files\\7-Zip\\7za.exe",
            "/usr/bin/7z", "/usr/bin/7za", "/usr/bin/7zz", "/usr/bin/7zr",
            "/usr/local/bin/7z", "/usr/local/bin/7za", "/usr/local/bin/7zz",
            "/snap/bin/7z", "/opt/homebrew/bin/7z", "/opt/local/bin/7z");

    private SevenZipLocator() {
    }

    /**
     * @param configured 配置文件中指定的路径，可为空
     * @return 可用的 7z 可执行文件，未找到返回 null
     */
    public static Path locate(String configured, Logger log) {
        List<Path> candidates = new ArrayList<>();

        if (configured != null && !configured.isBlank()) {
            Path path = Path.of(configured.trim());
            if (Files.isRegularFile(path)) {
                candidates.add(path);
            } else if (log != null) {
                log.warning("compression.seven-zip-path 指向的文件不存在：" + configured);
            }
        }

        candidates.addAll(findOnPath());
        for (String common : COMMON_PATHS) {
            Path path = Path.of(common);
            if (Files.isRegularFile(path)) {
                candidates.add(path);
            }
        }

        for (Path candidate : candidates) {
            if (isSevenZip(candidate)) {
                if (log != null) {
                    log.info("已找到 7-Zip：" + candidate);
                }
                return candidate;
            }
        }
        return null;
    }

    private static List<Path> findOnPath() {
        List<Path> found = new ArrayList<>();
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) {
            return found;
        }
        String separator = System.getProperty("path.separator", ":");
        for (String dir : pathEnv.split(java.util.regex.Pattern.quote(separator))) {
            if (dir.isBlank()) {
                continue;
            }
            Path base;
            try {
                base = Path.of(dir);
            } catch (Exception e) {
                continue;
            }
            for (String name : EXECUTABLE_NAMES) {
                Path candidate = base.resolve(name);
                if (Files.isRegularFile(candidate)) {
                    found.add(candidate);
                }
            }
        }
        return found;
    }

    /** 运行一次可执行文件（无参数），通过输出判断它是否真的是 7-Zip。 */
    public static boolean isSevenZip(Path executable) {
        try {
            Process process = new ProcessBuilder(executable.toAbsolutePath().toString()).start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), Charset.defaultCharset()))) {
                String line;
                while ((line = reader.readLine()) != null && output.length() < 4000) {
                    output.append(line).append('\n');
                }
            }
            if (!process.waitFor(8, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
            String text = output.toString().toLowerCase(Locale.ROOT);
            return text.contains("7-zip") || text.contains("7zr") || text.contains("p7zip");
        } catch (Exception e) {
            return false;
        }
    }
}
