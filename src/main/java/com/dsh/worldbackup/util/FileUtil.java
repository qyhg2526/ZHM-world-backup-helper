package com.dsh.worldbackup.util;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.DecimalFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** 文件相关的通用工具。 */
public final class FileUtil {

    private static final DecimalFormat SIZE_FORMAT = new DecimalFormat("0.##");
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private FileUtil() {
    }

    /** 人类可读的文件大小。 */
    public static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double value = bytes;
        String[] units = {"KB", "MB", "GB", "TB"};
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return SIZE_FORMAT.format(value) + " " + units[unit];
    }

    /** 人类可读的耗时。 */
    public static String formatDuration(long millis) {
        if (millis < 1000) {
            return millis + " 毫秒";
        }
        long totalSeconds = millis / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        StringBuilder sb = new StringBuilder();
        if (hours > 0) {
            sb.append(hours).append(" 小时 ");
        }
        if (minutes > 0) {
            sb.append(minutes).append(" 分 ");
        }
        sb.append(seconds).append(" 秒");
        return sb.toString().trim();
    }

    public static String formatDate(long epochMillis) {
        return DATE_FORMAT.format(Instant.ofEpochMilli(epochMillis));
    }

    /** 通配符匹配（* 与 ?），Windows 下忽略大小写。 */
    public static boolean matches(String pattern, String value) {
        if (pattern == null || value == null) {
            return false;
        }
        String regex = wildcardToRegex(pattern);
        int flags = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        return Pattern.compile(regex, flags).matcher(value).matches();
    }

    private static String wildcardToRegex(String pattern) {
        StringBuilder sb = new StringBuilder(pattern.length() + 8);
        sb.append('^');
        for (char c : pattern.toCharArray()) {
            switch (c) {
                case '*' -> sb.append(".*");
                case '?' -> sb.append('.');
                case '.', '(', ')', '+', '|', '^', '$', '@', '%', '{', '}', '[', ']', '\\' ->
                        sb.append('\\').append(c);
                default -> sb.append(c);
            }
        }
        sb.append('$');
        return sb.toString();
    }

    /** 递归删除目录或文件。 */
    public static void deleteRecursively(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            return;
        }
        if (Files.isDirectory(path)) {
            Files.walkFileTree(path, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } else {
            Files.deleteIfExists(path);
        }
    }

    /** 目录中符合通配符的文件，按最后修改时间倒序（最新在前）。 */
    public static List<Path> listFiles(Path dir, String pattern) throws IOException {
        List<Path> result = new ArrayList<>();
        if (dir == null || !Files.isDirectory(dir)) {
            return result;
        }
        try (var stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> pattern == null || pattern.isBlank()
                            || matches(pattern, p.getFileName().toString()))
                    .forEach(result::add);
        }
        result.sort(Comparator.comparingLong((Path p) -> {
            try {
                return Files.getLastModifiedTime(p).toMillis();
            } catch (IOException e) {
                return 0L;
            }
        }).reversed().thenComparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)));
        return result;
    }

    /** 目录总大小。 */
    public static long directorySize(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return 0L;
        }
        final long[] total = {0L};
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    total[0] += attrs.size();
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // 忽略无法统计的部分
        }
        return total[0];
    }

    /** 安全的文件名（去掉非法字符）。 */
    public static String sanitizeFileName(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }
}
