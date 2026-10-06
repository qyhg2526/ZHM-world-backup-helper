package com.dsh.worldbackup.util;

/** 任务被用户取消。 */
public class CancelledException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CancelledException(String message) {
        super(message);
    }
}
