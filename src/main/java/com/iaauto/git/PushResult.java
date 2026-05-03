package com.iaauto.git;

public record PushResult(
        boolean committed,
        String branch,
        String repositoryDirectory,
        String repositoryFile
) {
}
