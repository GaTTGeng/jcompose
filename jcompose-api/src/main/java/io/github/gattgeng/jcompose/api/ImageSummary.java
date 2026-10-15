package io.github.gattgeng.jcompose.api;

import java.time.Instant;

/** Summary of one image used by a project. */
public record ImageSummary(String id, String repository, String tag, String platform,
                           long size, Instant created, Instant lastTagTime) { }
