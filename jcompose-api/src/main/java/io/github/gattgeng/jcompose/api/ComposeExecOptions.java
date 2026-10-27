package io.github.gattgeng.jcompose.api;

import java.util.List;
import java.util.Objects;

/**
 * Options for executing a command inside a service container.
 * @param command Command and arguments to run inside the container.
 * @param detach Run detached and return immediately instead of capturing output.
 * @param env Extra environment variables for the command, in <code>NAME=value</code> form.
 * @param index Replica index within the service; null targets the first matching container.
 * @param privileged Run the command with extended privileges.
 * @param user User (name or UID) the command runs as.
 * @param workdir Working directory for the command inside the container.
 * @param tty Allocate a TTY for the command.
 * @param interactive Keep standard input open for the command.
 * @param detachKeys Key sequence that detaches the client, in Docker's detach-keys format; null uses Docker's default.
 */
public record ComposeExecOptions(
        List<String> command,
        boolean detach,
        List<String> env,
        Integer index,
        boolean privileged,
        String user,
        String workdir,
        boolean tty,
        boolean interactive,
        String detachKeys) {
    public ComposeExecOptions {
        Objects.requireNonNull(command, "command");
        command = command == null ? null : List.copyOf(command);
        env = env == null ? null : List.copyOf(env);
    }

    /** Preserves the original constructor for callers compiled against the initial options shape. */
    public ComposeExecOptions(List<String> command, boolean detach, List<String> env, Integer index,
                              boolean privileged, String user, String workdir, boolean tty, boolean interactive) {
        this(command, detach, env, index, privileged, user, workdir, tty, interactive, null);
    }

    public static Builder builder() { return new Builder(); }

    /** Fluent builder for constructing operation options with Compose defaults. */
    public static final class Builder {
        private List<String> command;
        private boolean detach;
        private List<String> env;
        private Integer index;
        private boolean privileged;
        private String user;
        private String workdir;
        private boolean tty = true;
        private boolean interactive = true;
        private String detachKeys;

        /**
         * Command and arguments to run inside the container.
         * @param value Command and arguments to run inside the container.
         * @return this builder
         */
        public Builder command(List<String> value) { this.command = value; return this; }

        /**
         * Run detached and return immediately instead of capturing output.
         * @param value Run detached and return immediately instead of capturing output.
         * @return this builder
         */
        public Builder detach(boolean value) { this.detach = value; return this; }

        /**
         * Extra environment variables for the command, in <code>NAME=value</code> form.
         * @param value Extra environment variables for the command, in <code>NAME=value</code> form.
         * @return this builder
         */
        public Builder env(List<String> value) { this.env = value; return this; }

        /**
         * Replica index within the service; null targets the first matching container.
         * @param value Replica index within the service; null targets the first matching container.
         * @return this builder
         */
        public Builder index(Integer value) { this.index = value; return this; }

        /**
         * Run the command with extended privileges.
         * @param value Run the command with extended privileges.
         * @return this builder
         */
        public Builder privileged(boolean value) { this.privileged = value; return this; }

        /**
         * User (name or UID) the command runs as.
         * @param value User (name or UID) the command runs as.
         * @return this builder
         */
        public Builder user(String value) { this.user = value; return this; }

        /**
         * Working directory for the command inside the container.
         * @param value Working directory for the command inside the container.
         * @return this builder
         */
        public Builder workdir(String value) { this.workdir = value; return this; }

        /**
         * Allocate a TTY for the command.
         * @param value Allocate a TTY for the command.
         * @return this builder
         */
        public Builder tty(boolean value) { this.tty = value; return this; }

        /**
         * Keep standard input open for the command.
         * @param value Keep standard input open for the command.
         * @return this builder
         */
        public Builder interactive(boolean value) { this.interactive = value; return this; }

        /**
         * Key sequence that detaches the client, in Docker's detach-keys format.
         * @param value Key sequence that detaches the client; null uses Docker's default
         * @return this builder
         */
        public Builder detachKeys(String value) { this.detachKeys = value; return this; }

        /** @return the immutable operation options */
        public ComposeExecOptions build() { return new ComposeExecOptions(command, detach, env, index, privileged, user, workdir, tty, interactive, detachKeys); }
    }
}
