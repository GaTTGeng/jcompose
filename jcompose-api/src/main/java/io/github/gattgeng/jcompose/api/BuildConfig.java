package io.github.gattgeng.jcompose.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Typed projection of a service {@code build} definition.
 * @param context Build context path, resolved relative to the Compose file directory.
 * @param dockerfile Dockerfile path relative to the build context.
 * @param args Build arguments; a null value means the argument is inherited from the environment.
 * @param cacheFrom Build cache sources.
 * @param cacheTo Build cache export targets.
 * @param target Multi-stage build target.
 * @param tags Additional image tags applied to the built image.
 * @param labels Labels applied to the built image.
 * @param network Network mode used during the build.
 * @param extraHosts Host mappings made available during the build.
 * @param privileged Whether to use privileged build mode.
 * @param shmSize Shared memory size for the build container.
 * @param platforms Target platform selectors.
 * @param pull Whether to force pulling base images.
 * @param noCache Whether to disable the build cache.
 * @param contextDirectory Auxiliary build context directory metadata.
 */
public record BuildConfig(
        String context,
        String dockerfile,
        Map<String, String> args,
        List<String> cacheFrom,
        List<String> cacheTo,
        String target,
        List<String> tags,
        Map<String, String> labels,
        String network,
        Map<String, String> extraHosts,
        Boolean privileged,
        String shmSize,
        List<String> platforms,
        Boolean pull,
        Boolean noCache,
        String contextDirectory) {
    public BuildConfig {
        args = nullableMap(args);
        cacheFrom = nullableList(cacheFrom);
        cacheTo = nullableList(cacheTo);
        tags = nullableList(tags);
        labels = nullableMap(labels);
        extraHosts = nullableMap(extraHosts);
        platforms = nullableList(platforms);
    }

    private static <T> List<T> nullableList(List<T> values) {
        return values == null ? null : List.copyOf(values);
    }

    private static <T> Map<String, T> nullableMap(Map<String, T> values) {
        return values == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
}
