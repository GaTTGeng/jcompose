package io.github.gattgeng.jcompose.api;

import java.util.List;
import java.util.Objects;

/**
 * Options for running a one-off container for a service.
 * @param command Command overriding the service image's default command.
 * @param entrypoint Entrypoint overriding the service image's default entrypoint.
 * @param name Explicit container name; defaults to a generated project-service-index name.
 * @param env Extra environment variables for the container, in <code>NAME=value</code> form.
 * @param labels Extra labels applied to the one-off container, in <code>key=value</code> form.
 * @param volumes Extra bind/volume mounts applied to the container, in <code>source:target[:mode]</code> form.
 * @param remove Remove the container automatically when it exits.
 * @param detach Run detached and return the container ID immediately.
 * @param noDeps Do not start the service's dependency services first.
 * @param tty Allocate a TTY for the container.
 * @param interactive Keep standard input open and forward process standard input in attached mode.
 * @param workdir Working directory inside the container.
 * @param user User (name or UID) the container process runs as.
 * @param capAdd Linux capabilities to add to the container.
 * @param capDrop Linux capabilities to drop from the container.
 * @param privileged Run the container with extended privileges.
 * @param useNetworkAliases Connect the container to the service's network aliases so dependents can resolve it.
 * @param index Replica index used in the generated container name and labels.
 * @param servicePorts Publish ports declared by the service.
 * @param publish Extra port mappings in Docker Compose run short syntax.
 */
public record ComposeRunOptions(
        List<String> command,
        String entrypoint,
        String name,
        List<String> env,
        List<String> labels,
        List<String> volumes,
        boolean remove,
        boolean detach,
        boolean noDeps,
        boolean tty,
        boolean interactive,
        String workdir,
        String user,
        List<String> capAdd,
        List<String> capDrop,
        boolean privileged,
        boolean useNetworkAliases,
        Integer index,
        boolean servicePorts,
        List<String> publish) {
    public ComposeRunOptions {
        command = command == null ? null : List.copyOf(command);
        env = env == null ? null : List.copyOf(env);
        labels = labels == null ? null : List.copyOf(labels);
        volumes = volumes == null ? null : List.copyOf(volumes);
        capAdd = capAdd == null ? null : List.copyOf(capAdd);
        capDrop = capDrop == null ? null : List.copyOf(capDrop);
        publish = publish == null ? null : List.copyOf(publish);
    }

    /** Keeps the previous constructor contract; service ports remain unpublished by default. */
    public ComposeRunOptions(List<String> command, String entrypoint, String name, List<String> env,
            List<String> labels, List<String> volumes, boolean remove, boolean detach, boolean noDeps,
            boolean tty, boolean interactive, String workdir, String user, List<String> capAdd,
            List<String> capDrop, boolean privileged, boolean useNetworkAliases, Integer index) {
        this(command, entrypoint, name, env, labels, volumes, remove, detach, noDeps, tty, interactive,
                workdir, user, capAdd, capDrop, privileged, useNetworkAliases, index, false);
    }

    /** Keeps the service-port constructor contract for callers built after servicePorts was added. */
    public ComposeRunOptions(List<String> command, String entrypoint, String name, List<String> env,
            List<String> labels, List<String> volumes, boolean remove, boolean detach, boolean noDeps,
            boolean tty, boolean interactive, String workdir, String user, List<String> capAdd,
            List<String> capDrop, boolean privileged, boolean useNetworkAliases, Integer index,
            boolean servicePorts) {
        this(command, entrypoint, name, env, labels, volumes, remove, detach, noDeps, tty, interactive,
                workdir, user, capAdd, capDrop, privileged, useNetworkAliases, index, servicePorts, null);
    }

    public static Builder builder() { return new Builder(); }

    /** Fluent builder for constructing operation options with Compose defaults. */
    public static final class Builder {
        private List<String> command;
        private String entrypoint;
        private String name;
        private List<String> env;
        private List<String> labels;
        private List<String> volumes;
        private boolean remove;
        private boolean detach;
        private boolean noDeps;
        private boolean tty = true;
        private boolean interactive;
        private String workdir;
        private String user;
        private List<String> capAdd;
        private List<String> capDrop;
        private boolean privileged;
        private boolean useNetworkAliases;
        private Integer index;
        private boolean servicePorts;
        private List<String> publish;

        /**
         * Command overriding the service image's default command.
         * @param value Command overriding the service image's default command.
         * @return this builder
         */
        public Builder command(List<String> value) { this.command = value; return this; }

        /**
         * Entrypoint overriding the service image's default entrypoint.
         * @param value Entrypoint overriding the service image's default entrypoint.
         * @return this builder
         */
        public Builder entrypoint(String value) { this.entrypoint = value; return this; }

        /**
         * Explicit container name; defaults to a generated project-service-index name.
         * @param value Explicit container name; defaults to a generated project-service-index name.
         * @return this builder
         */
        public Builder name(String value) { this.name = value; return this; }

        /**
         * Extra environment variables for the container, in <code>NAME=value</code> form.
         * @param value Extra environment variables for the container, in <code>NAME=value</code> form.
         * @return this builder
         */
        public Builder env(List<String> value) { this.env = value; return this; }

        /**
         * Extra labels applied to the one-off container, in <code>key=value</code> form.
         * @param value Extra labels applied to the one-off container, in <code>key=value</code> form.
         * @return this builder
         */
        public Builder labels(List<String> value) { this.labels = value; return this; }

        /**
         * Extra bind/volume mounts applied to the container, in <code>source:target[:mode]</code> form.
         * @param value Extra bind/volume mounts applied to the container, in <code>source:target[:mode]</code> form.
         * @return this builder
         */
        public Builder volumes(List<String> value) { this.volumes = value; return this; }

        /**
         * Remove the container automatically when it exits.
         * @param value Remove the container automatically when it exits.
         * @return this builder
         */
        public Builder remove(boolean value) { this.remove = value; return this; }

        /**
         * Run detached and return the container ID immediately.
         * @param value Run detached and return the container ID immediately.
         * @return this builder
         */
        public Builder detach(boolean value) { this.detach = value; return this; }

        /**
         * Do not start the service's dependency services first.
         * @param value Do not start the service's dependency services first.
         * @return this builder
         */
        public Builder noDeps(boolean value) { this.noDeps = value; return this; }

        /**
         * Allocate a TTY for the container.
         * @param value Allocate a TTY for the container.
         * @return this builder
         */
        public Builder tty(boolean value) { this.tty = value; return this; }

        /**
         * Keep standard input open and forward process standard input in attached mode.
         * @param value Keep standard input open and forward process standard input in attached mode.
         * @return this builder
         */
        public Builder interactive(boolean value) { this.interactive = value; return this; }

        /**
         * Working directory inside the container.
         * @param value Working directory inside the container.
         * @return this builder
         */
        public Builder workdir(String value) { this.workdir = value; return this; }

        /**
         * User (name or UID) the container process runs as.
         * @param value User (name or UID) the container process runs as.
         * @return this builder
         */
        public Builder user(String value) { this.user = value; return this; }

        /**
         * Linux capabilities to add to the container.
         * @param value Linux capabilities to add to the container.
         * @return this builder
         */
        public Builder capAdd(List<String> value) { this.capAdd = value; return this; }

        /**
         * Linux capabilities to drop from the container.
         * @param value Linux capabilities to drop from the container.
         * @return this builder
         */
        public Builder capDrop(List<String> value) { this.capDrop = value; return this; }

        /**
         * Run the container with extended privileges.
         * @param value Run the container with extended privileges.
         * @return this builder
         */
        public Builder privileged(boolean value) { this.privileged = value; return this; }

        /**
         * Connect the container to the service's network aliases so dependents can resolve it.
         * @param value Connect the container to the service's network aliases so dependents can resolve it.
         * @return this builder
         */
        public Builder useNetworkAliases(boolean value) { this.useNetworkAliases = value; return this; }

        /** Publish ports declared by the service, matching Compose run's --service-ports option. */
        public Builder servicePorts(boolean value) { this.servicePorts = value; return this; }

        /** Publish extra container ports using Docker Compose run short syntax. */
        public Builder publish(List<String> value) { this.publish = value; return this; }

        /**
         * Replica index used in the generated container name and labels.
         * @param value Replica index used in the generated container name and labels.
         * @return this builder
         */
        public Builder index(Integer value) { this.index = value; return this; }

        /** @return the immutable operation options */
        public ComposeRunOptions build() { return new ComposeRunOptions(command, entrypoint, name, env, labels, volumes, remove, detach, noDeps, tty, interactive, workdir, user, capAdd, capDrop, privileged, useNetworkAliases, index, servicePorts, publish); }
    }
}
