package io.github.gattgeng.jcompose.api;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Typed projection of a service {@code deploy} block.
 * @param replicas Desired replica count.
 * @param resources Deployment resource limits and reservations.
 * @param restartPolicy Swarm-style restart policy.
 * @param placement Placement constraints.
 * @param updateConfig Rolling-update settings.
 * @param rollbackConfig Rollback settings.
 * @param labels Labels attached to the deployed service.
 * @param mode Orchestration mode such as {@code replicated} or {@code global}.
 * @param endpointMode Mapping-form endpoint mode configuration.
 */
public record DeployConfig(Integer replicas, Resources resources, RestartPolicy restartPolicy, Placement placement,
                           UpdateConfig updateConfig, RollbackConfig rollbackConfig, Map<String, String> labels,
                           String mode, EndpointMode endpointMode) {
    public DeployConfig {
        labels = labels == null ? null : Map.copyOf(labels);
    }

    /** Resource limits and reservations under {@code deploy.resources}.
     * @param limits Hard resource ceilings.
     * @param reservations Guaranteed resource floors.
     */
    public record Resources(Limits limits, Reservations reservations) { }

    /** Resource limits.
     * @param memory Memory ceiling in bytes.
     * @param nanoCpus Literal {@code nano_cpus} value.
     * @param cpuCount CPU count from the {@code cpus} key.
     */
    public record Limits(Long memory, String nanoCpus, Long cpuCount) { }

    /** Resource reservations.
     * @param memory Reserved memory in bytes.
     * @param nanoCpus Literal {@code nano_cpus} value.
     */
    public record Reservations(Long memory, String nanoCpus) { }

    /** Swarm restart policy, distinct from the service-level {@code restart} field.
     * @param condition Policy condition such as {@code on-failure}.
     * @param maxRetries Retry ceiling.
     * @param delay Delay between attempts.
     * @param window Window in which failures count.
     * @param period Additional Swarm-only period.
     */
    public record RestartPolicy(String condition, Integer maxRetries, Duration delay, Duration window,
                                Duration period) { }

    /** Placement constraints.
     * @param constraints Constraint expressions such as node labels.
     */
    public record Placement(List<String> constraints) {
        public Placement {
            constraints = constraints == null ? null : List.copyOf(constraints);
        }
    }

    /** Rolling-update settings.
     * @param parallelism Parallelism limit, preserved as written.
     * @param delay Delay between updates.
     * @param order Update order.
     * @param monitor Failure monitoring duration.
     * @param failureAction Action after an update failure.
     * @param maxFailureRatio Maximum tolerated failure ratio.
     */
    public record UpdateConfig(String parallelism, Duration delay, String order, Duration monitor,
                               String failureAction, Double maxFailureRatio) { }

    /** Rollback settings.
     * @param parallelism Parallelism limit, preserved as written.
     * @param delay Delay between rollbacks.
     * @param order Rollback order.
     * @param monitor Failure monitoring duration.
     * @param failureAction Action after a rollback failure.
     * @param maxFailureRatio Maximum tolerated failure ratio.
     */
    public record RollbackConfig(String parallelism, Duration delay, String order, Duration monitor,
                                 String failureAction, Double maxFailureRatio) { }

    /** Mapping-form endpoint mode.
     * @param mode Endpoint mode name.
     */
    public record EndpointMode(String mode) { }
}
