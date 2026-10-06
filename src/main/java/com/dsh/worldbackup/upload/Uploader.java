package com.dsh.worldbackup.upload;

import com.dsh.worldbackup.compress.ProgressCallback;

import java.nio.file.Path;

/** 上传目标抽象。 */
public interface Uploader {

    /**
     * 上传文件。
     *
     * @param file       本地文件
     * @param remoteName 远端文件名
     * @param callback   进度回调
     */
    UploadResult upload(Path file, String remoteName, ProgressCallback callback) throws Exception;

    /**
     * 清理远端旧备份。
     *
     * @param namePattern 文件名通配符
     * @param keepCount   保留数量，&lt;=0 表示不清理
     * @return 删除的文件数量
     */
    int cleanupRemote(String namePattern, int keepCount) throws Exception;

    /** 请求取消。 */
    void cancel();

    /** 用于日志展示的描述。 */
    String describe();

    /** 释放资源（默认无操作）。 */
    default void close() throws Exception {
    }
}
