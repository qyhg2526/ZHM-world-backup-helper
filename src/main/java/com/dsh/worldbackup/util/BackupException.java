package com.dsh.worldbackup.util;

/** 备份流程中可预期的错误（会以友好提示展示给管理员）。 */
public class BackupException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public BackupException(String message) {
        super(message);
    }

    public BackupException(String message, Throwable cause) {
        super(message, cause);
    }
}
