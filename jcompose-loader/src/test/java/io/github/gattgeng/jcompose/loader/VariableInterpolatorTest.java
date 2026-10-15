package io.github.gattgeng.jcompose.loader;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VariableInterpolatorTest {
    @Test
    void expandsShellAndBracedVariablesAndEscapesDoubleDollar() {
        String value = VariableInterpolator.expand("$SET/${SET}/$$SET", Map.of(), Map.of("SET", "value"));
        assertEquals("value/value/$SET", value);
    }

    @Test
    void implementsComposeDefaultAndAlternativeOperators() {
        Map<String, String> environment = Map.of("SET", "value", "EMPTY", "");
        String value = VariableInterpolator.expand(
                "${MISSING-default}|${EMPTY-default}|${EMPTY:-fallback}|${SET+alternate}|${MISSING+alternate}|${EMPTY:+alternate}",
                Map.of(), environment);
        assertEquals("default||fallback|alternate||", value);
    }

    @Test
    void requiredOperatorsFailOnlyWhenTheirUnsetConditionMatches() {
        assertEquals("value", VariableInterpolator.expand("${SET:?required}", Map.of(), Map.of("SET", "value")));
        var exception = assertThrows(IllegalArgumentException.class,
                () -> VariableInterpolator.expand("${EMPTY:?provide a value}", Map.of(), Map.of("EMPTY", "")));
        assertTrue(exception.getMessage().contains("EMPTY"));
        assertTrue(exception.getMessage().contains("provide a value"));
    }

    @Test
    void processEnvironmentOverridesDotenvValues() {
        assertEquals("process", VariableInterpolator.expand("${VALUE}", Map.of("VALUE", "dotenv"), Map.of("VALUE", "process")));
    }
}
