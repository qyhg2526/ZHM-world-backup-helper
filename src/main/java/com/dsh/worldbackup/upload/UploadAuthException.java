package com.dsh.worldbackup.upload;

import com.dsh.worldbackup.util.BackupException;

/** 网盘认证失败（不重试）。 */
public class UploadAuthException extends BackupException {

    private static final long serialVersionUID = 1L;

    public UploadAuthException(String message) {
        super(message);
    }
}
