package com.iaauto.git;

public final class GitPushException extends Exception {
    public GitPushException(String message) {
        super(message);
    }

    public GitPushException(String message, Throwable cause) {
        super(message, cause);
    }
}
