package io.github.gattgeng.jcompose.api;

/** Outcome of tagging and pushing one service image to a registry. */
public record ImagePublishResult(
        String service,
        String sourceImage,
        String taggedImage,
        boolean tagged,
        boolean pushed,
        String error) {
    /** @return whether both tagging and pushing succeeded */
    public boolean succeeded() { return tagged && pushed; }
}
