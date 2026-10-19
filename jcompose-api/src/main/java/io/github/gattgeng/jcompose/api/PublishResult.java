package io.github.gattgeng.jcompose.api;

/** Reference and registry digest of a published Compose application OCI artifact. */
public record PublishResult(String reference, String digest) { }
