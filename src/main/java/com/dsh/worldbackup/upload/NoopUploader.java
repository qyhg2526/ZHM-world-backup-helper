package com.dsh.worldbackup.upload;

import com.dsh.worldbackup.compress.ProgressCallback;

import java.nio.file.Path;

/** 未启用上传时使用的空实现。 */
public final class NoopUploader implements Uploader {

    @Override
    public UploadResult upload(Path file, String remoteName, ProgressCallback callback) {
        return UploadResult.skipped("未启用 WebDAV 上传");
    }

    @Override
    public int cleanupRemote(String namePattern, int keepCount) {
        return 0;
    }

    @Override
    public void cancel() {
    }

    @Override
    public String describe() {
        return "未启用";
    }
}
