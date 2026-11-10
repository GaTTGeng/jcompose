package io.github.gattgeng.jcompose.api;

import java.time.Duration;
import java.util.List;

/** Typed projection of a service healthcheck.
 * @param disabled Whether the healthcheck is disabled.
 * @param test Healthcheck command; scalar shell commands use the {@code CMD-SHELL} prefix.
 * @param interval Delay between checks.
 * @param timeout Maximum duration for one check.
 * @param retries Consecutive failures tolerated before the container is unhealthy.
 * @param startPeriod Grace period during which failures do not count.
 * @param startInterval Delay between checks during the start period.
 */
public record ComposeHealthcheck(boolean disabled, List<String> test, Duration interval, Duration timeout,
                                 Integer retries, Duration startPeriod, Duration startInterval) {
    public ComposeHealthcheck {
        test = test == null ? List.of() : List.copyOf(test);
    }
}
