package com.iaauto.git;

import java.util.Objects;

public record PushProgress(double progress, String message) {
    public PushProgress {
        if (Double.isNaN(progress) || Double.isInfinite(progress)) {
            progress = 0.0D;
        }
        progress = Math.max(0.0D, Math.min(1.0D, progress));
        message = Objects.toString(message, "Working...");
    }
}
