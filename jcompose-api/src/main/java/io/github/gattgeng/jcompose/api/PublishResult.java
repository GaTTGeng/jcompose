package io.github.gattgeng.jcompose.api;

/** Outcome of tagging and pushing one service image during publish. */
public record PublishResult(String service, String sourceImage, String taggedImage,
                            boolean tagged, boolean pushed, String error) {
    public boolean succeeded() { return tagged && pushed; }
}
