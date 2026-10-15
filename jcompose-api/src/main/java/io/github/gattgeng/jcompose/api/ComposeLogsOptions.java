package io.github.gattgeng.jcompose.api;

import java.util.List;
import java.util.Objects;

/**
 * Options controlling which container log lines are streamed.
 * @param services Restricts log streaming to these service names; null covers every project service.
 * @param follow Keep streaming new lines after the existing backlog is delivered.
 * @param since Only include lines at or after this timestamp or duration; currently retained but not applied by the engine.
 * @param until Only include lines at or before this timestamp or duration; currently retained but not applied by the engine.
 * @param timestamps Prefix each line with its timestamp.
 * @param tail Number of trailing lines to deliver, or <code>all</code> for the full backlog.
 * @param noColor Strip ANSI color codes from the streamed lines.
 * @param noLogPrefix Drop the per-line service-name prefix; currently has no effect because the consumer receives service separately.
 */
public record ComposeLogsOptions(
        List<String> services,
        boolean follow,
        String since,
        String until,
        boolean timestamps,
        String tail,
        boolean noColor,
        boolean noLogPrefix) {
    public ComposeLogsOptions {
        services = services == null ? null : List.copyOf(services);
    }

    public static Builder builder() { return new Builder(); }

    /** Fluent builder for constructing operation options with Compose defaults. */
    public static final class Builder {
        private List<String> services;
        private boolean follow;
        private String since;
        private String until;
        private boolean timestamps;
        private String tail = "all";
        private boolean noColor;
        private boolean noLogPrefix;

        /**
         * Restricts log streaming to these service names; null covers every project service.
         * @param value Restricts log streaming to these service names; null covers every project service.
         * @return this builder
         */
        public Builder services(List<String> value) { this.services = value; return this; }

        /**
         * Keep streaming new lines after the existing backlog is delivered.
         * @param value Keep streaming new lines after the existing backlog is delivered.
         * @return this builder
         */
        public Builder follow(boolean value) { this.follow = value; return this; }

        /**
         * Only include lines at or after this timestamp or duration; currently retained but not applied by the engine.
         * @param value Only include lines at or after this timestamp or duration (for example <code>2024-01-01</code> or <code>10m</code>).
         * @return this builder
         */
        public Builder since(String value) { this.since = value; return this; }

        /**
         * Only include lines at or before this timestamp or duration; currently retained but not applied by the engine.
         * @param value Only include lines at or before this timestamp or duration.
         * @return this builder
         */
        public Builder until(String value) { this.until = value; return this; }

        /**
         * Prefix each line with its timestamp.
         * @param value Prefix each line with its timestamp.
         * @return this builder
         */
        public Builder timestamps(boolean value) { this.timestamps = value; return this; }

        /**
         * Number of trailing lines to deliver, or <code>all</code> for the full backlog.
         * @param value Number of trailing lines to deliver, or <code>all</code> for the full backlog.
         * @return this builder
         */
        public Builder tail(String value) { this.tail = value; return this; }

        /**
         * Strip ANSI color codes from the streamed lines.
         * @param value Strip ANSI color codes from the streamed lines.
         * @return this builder
         */
        public Builder noColor(boolean value) { this.noColor = value; return this; }

        /**
         * Drop the per-line service-name prefix; currently has no effect because the consumer receives service separately.
         * @param value Drop the per-line service-name prefix.
         * @return this builder
         */
        public Builder noLogPrefix(boolean value) { this.noLogPrefix = value; return this; }

        /** @return the immutable operation options */
        public ComposeLogsOptions build() { return new ComposeLogsOptions(services, follow, since, until, timestamps, tail, noColor, noLogPrefix); }
    }
}
