package com.dsh.worldbackup.compress;

import java.nio.file.Path;

/**
 * 压缩结果。
 *
 * @param archive        压缩包路径
 * @param archiveBytes   压缩包大小
 * @param sourceBytes    原始数据大小
 * @param fileCount      文件数量
 * @param durationMillis 压缩耗时
 * @param compressorName 使用的压缩引擎名称
 */
public record CompressionResult(Path archive,
                                long archiveBytes,
                                long sourceBytes,
                                long fileCount,
                                long durationMillis,
                                String compressorName) {

    /** 压缩率（1 - 压缩后 / 原始）。 */
    public double ratio() {
        if (sourceBytes <= 0) {
            return 0.0;
        }
        return 1.0 - (double) archiveBytes / (double) sourceBytes;
    }
}
