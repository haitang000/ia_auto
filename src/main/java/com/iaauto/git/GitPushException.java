package com.iaauto.git;

public final class GitPushException extends Exception {
    private final boolean retryable;

    public GitPushException(String message) {
        this(message, false);
    }

    public GitPushException(String message, Throwable cause) {
        this(message, cause, false);
    }

    public GitPushException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public GitPushException(String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }
}
