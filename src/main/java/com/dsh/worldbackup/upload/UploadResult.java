package com.dsh.worldbackup.upload;

import java.nio.file.Path;

/**
 * 上传结果。
 *
 * @param uploaded       是否真正上传
 * @param remoteLocation 远端位置（URL 或说明）
 * @param bytes          上传字节数
 * @param durationMillis 耗时
 * @param detail         附加说明
 */
public record UploadResult(boolean uploaded, String remoteLocation, long bytes, long durationMillis, String detail) {

    public static UploadResult skipped(String reason) {
        return new UploadResult(false, "", 0L, 0L, reason);
    }
}
