package com.dsh.worldbackup.compress;

/**
 * 压缩 / 上传进度回调。
 */
@FunctionalInterface
public interface ProgressCallback {

    /**
     * @param processedBytes 已处理字节数
     * @param totalBytes     总字节数（可能为 0，表示未知）
     * @param ratio          0.0 - 1.0 的比例
     */
    void onProgress(long processedBytes, long totalBytes, double ratio);
}
