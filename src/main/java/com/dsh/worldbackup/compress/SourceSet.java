package com.dsh.worldbackup.compress;

import com.dsh.worldbackup.util.FileUtil;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * 备份源集合：负责扫描目录、应用排除规则并统计大小，供各压缩引擎复用。
 */
public final class SourceSet {

    /**
     * 压缩包中的一个条目。
     *
     * @param path        磁盘路径
     * @param archivePath 压缩包内路径（使用 / 分隔）
     * @param directory   是否为目录
     * @param size        文件大小（目录为 0）
     */
    public record TreeItem(Path path, String archivePath, boolean directory, long size) {
    }

    private final List<SourceEntry> entries;
    private final Map<SourceEntry, List<TreeItem>> tree;
    private final long totalBytes;
    private final long totalFiles;
    private final long totalDirs;

    private SourceSet(List<SourceEntry> entries,
                      Map<SourceEntry, List<TreeItem>> tree,
                      long totalBytes,
                      long totalFiles,
                      long totalDirs) {
        this.entries = entries;
        this.tree = tree;
        this.totalBytes = totalBytes;
        this.totalFiles = totalFiles;
        this.totalDirs = totalDirs;
    }

    /** 扫描全部源目录并统计。 */
    public static SourceSet scan(List<SourceEntry> entries, List<String> excludes, Logger log) throws IOException {
        Map<SourceEntry, List<TreeItem>> tree = new LinkedHashMap<>();
        long bytes = 0L;
        long files = 0L;
        long dirs = 0L;
        for (SourceEntry entry : entries) {
            List<TreeItem> items = collect(entry, excludes);
            tree.put(entry, items);
            for (TreeItem item : items) {
                if (item.directory()) {
                    dirs++;
                } else {
                    files++;
                    bytes += item.size();
                }
            }
            if (log != null) {
                log.info("已扫描 " + entry.archiveName() + "：" + items.size() + " 个条目，"
                        + FileUtil.formatSize(items.stream().mapToLong(TreeItem::size).sum()));
            }
        }
        return new SourceSet(List.copyOf(entries), tree, bytes, files, dirs);
    }

    private static List<TreeItem> collect(SourceEntry entry, List<String> excludes) throws IOException {
        Path root = entry.path();
        List<TreeItem> items = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(root) && isExcluded(root, dir, excludes)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                items.add(new TreeItem(dir, archivePath(root, entry.archiveName(), dir), true, 0L));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (isExcluded(root, file, excludes)) {
                    return FileVisitResult.CONTINUE;
                }
                items.add(new TreeItem(file, archivePath(root, entry.archiveName(), file), false, attrs.size()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
        items.sort(Comparator.comparing(TreeItem::archivePath));
        return items;
    }

    private static String archivePath(Path root, String archiveName, Path path) {
        if (path.equals(root)) {
            return archiveName;
        }
        String relative = root.relativize(path).toString().replace('\\', '/');
        return archiveName + "/" + relative;
    }

    /** 排除规则：同时匹配文件名与相对路径，支持 * 和 ? 通配符。 */
    public static boolean isExcluded(Path root, Path path, List<String> patterns) {
        if (patterns == null || patterns.isEmpty()) {
            return false;
        }
        Path fileName = path.getFileName();
        String name = fileName == null ? "" : fileName.toString();
        String relative;
        try {
            relative = root.relativize(path).toString().replace('\\', '/');
        } catch (IllegalArgumentException e) {
            relative = name;
        }
        for (String pattern : patterns) {
            if (pattern == null || pattern.isBlank()) {
                continue;
            }
            if (FileUtil.matches(pattern, name) || FileUtil.matches(pattern, relative)) {
                return true;
            }
        }
        return false;
    }

    public List<SourceEntry> entries() {
        return entries;
    }

    public List<TreeItem> items(SourceEntry entry) {
        return tree.getOrDefault(entry, List.of());
    }

    public long totalBytes() {
        return totalBytes;
    }

    public long totalFiles() {
        return totalFiles;
    }

    public long totalDirs() {
        return totalDirs;
    }
}
