package com.dsh.worldbackup.compress;

import java.nio.file.Path;

/**
 * 压缩引擎抽象。每个备份任务创建一个实例，任务结束后不再复用。
 */
public interface Compressor {

    /** 用于日志展示的引擎名称。 */
    String displayName();

    /** 生成的压缩包扩展名，例如 .7z / .zip。 */
    String extension();

    /**
     * 执行压缩。
     *
     * @param set      已扫描的源集合
     * @param archive  目标压缩包
     * @param callback 进度回调
     */
    CompressionResult compress(SourceSet set, Path archive, ProgressCallback callback) throws Exception;

    /** 请求取消（中断外部进程等）。 */
    void cancel();

    /** 释放资源（默认无操作）。 */
    default void close() throws Exception {
    }
}
