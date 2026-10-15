package io.github.gattgeng.jcompose.loader;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Expands the variable forms supported by ComposeSharp 3.0.0 before YAML parsing. */
final class VariableInterpolator {
    private static final Pattern VARIABLE = Pattern.compile(
            "\\$\\$|\\$\\{(?<name>[A-Za-z_][A-Za-z0-9_]*)(?:(?<op>:\\?|:\\+|:-|\\+|-|\\?)(?<fallback>[^}]*))?}|\\$(?<shellName>[A-Za-z_][A-Za-z0-9_]*)");

    private VariableInterpolator() { }

    static String expand(String text, Map<String, String> dotenv, Map<String, String> process) {
        return expand(text, dotenv, process, 0);
    }

    private static String expand(String text, Map<String, String> dotenv, Map<String, String> process, int depth) {
        if (depth > 100) throw new IllegalArgumentException("variable interpolation exceeded the maximum expansion depth");
        Matcher matcher = VARIABLE.matcher(text);
        StringBuilder result = new StringBuilder(text.length());
        while (matcher.find()) {
            String replacement;
            if (matcher.group().equals("$$")) {
                replacement = "$";
            } else {
                String name = matcher.group("shellName") != null ? matcher.group("shellName") : matcher.group("name");
                String value = process.containsKey(name) ? process.get(name) : dotenv.get(name);
                String operator = matcher.group("op");
                String fallback = matcher.group("fallback");
                if (operator == null) replacement = value == null ? "" : value;
                else replacement = switch (operator) {
                    case ":-" -> isUnsetOrEmpty(value) ? expand(fallback, dotenv, process, depth + 1) : value;
                    case "-" -> value == null ? expand(fallback, dotenv, process, depth + 1) : value;
                    case ":?" -> isUnsetOrEmpty(value) ? required(name, fallback) : value;
                    case "?" -> value == null ? required(name, fallback) : value;
                    case ":+" -> isUnsetOrEmpty(value) ? "" : expand(fallback, dotenv, process, depth + 1);
                    case "+" -> value == null ? "" : expand(fallback, dotenv, process, depth + 1);
                    default -> value == null ? "" : value;
                };
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static boolean isUnsetOrEmpty(String value) { return value == null || value.isEmpty(); }

    private static String required(String name, String message) {
        throw new IllegalArgumentException("Variable '" + name + "' is required"
                + (message == null || message.isBlank() ? "" : ": " + message));
    }
}
