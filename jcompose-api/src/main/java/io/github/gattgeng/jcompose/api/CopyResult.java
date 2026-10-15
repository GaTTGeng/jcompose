package io.github.gattgeng.jcompose.api;

/** Outcome of a host/container copy operation. */
public record CopyResult(byte[] content, boolean directory, long bytesCopied, int exitCode) {
    public CopyResult {
        content = content == null ? null : content.clone();
    }

    @Override public byte[] content() { return content == null ? null : content.clone(); }
}
