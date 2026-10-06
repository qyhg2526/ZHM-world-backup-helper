package com.dsh.worldbackup.compress;

import java.nio.file.Path;

/**
 * 一个待备份的根目录及其在压缩包内的名称。
 *
 * @param path        磁盘上的目录
 * @param archiveName 压缩包内的顶层名称（例如 world）
 */
public record SourceEntry(Path path, String archiveName) {
}
