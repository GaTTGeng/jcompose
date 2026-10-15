package io.github.gattgeng.jcompose.api;

/** Docker labels used to scope resources owned by one Compose project. */
public final class ComposeConstants {
    private ComposeConstants() { }
    public static final String PROJECT_LABEL = "com.docker.compose.project";
    public static final String SERVICE_LABEL = "com.docker.compose.service";
    public static final String CONTAINER_NUMBER_LABEL = "com.docker.compose.container-number";
    public static final String ONE_OFF_LABEL = "com.docker.compose.oneoff";
    public static final String CONFIG_HASH_LABEL = "com.docker.compose.config-hash";
    public static final String VERSION_LABEL = "com.docker.compose.version";
    public static final String WORKING_DIR_LABEL = "com.docker.compose.project.working_dir";
    public static final String CONFIG_FILES_LABEL = "com.docker.compose.project.config_files";
}
