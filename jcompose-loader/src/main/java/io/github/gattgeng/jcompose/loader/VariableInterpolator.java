package io.github.gattgeng.jcompose.loader;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Expands Docker Compose-style variables in YAML values and environment files. */
final class VariableInterpolator {
    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern EXPRESSION = Pattern.compile(
            "^([A-Za-z_][A-Za-z0-9_]*)(?:(:\\?|:\\+|:-|\\+|-|\\?)(.*))?$", Pattern.DOTALL);

    private VariableInterpolator() { }

    static String expand(String text, Map<String, String> dotenv, Map<String, String> process) {
        return expand(text, dotenv, process, 0);
    }

    private static String expand(String text, Map<String, String> dotenv, Map<String, String> process, int depth) {
        if (depth > 100) throw new IllegalArgumentException("variable interpolation exceeded the maximum expansion depth");
        StringBuilder result = new StringBuilder(text.length());
        for (int index = 0; index < text.length();) {
            if (text.charAt(index) != '$' || index + 1 >= text.length()) {
                result.append(text.charAt(index++));
                continue;
            }
            char next = text.charAt(index + 1);
            if (next == '$') {
                result.append('$');
                index += 2;
            } else if (next == '{') {
                int end = expressionEnd(text, index + 2);
                if (end < 0) {
                    throw new IllegalArgumentException("invalid interpolation format: " + text.substring(index));
                }
                String source = text.substring(index, end + 1);
                String expression = text.substring(index + 2, end);
                if (!EXPRESSION.matcher(expression).matches())
                    throw new IllegalArgumentException("invalid interpolation format: " + source);
                result.append(expandBraced(expression, source, dotenv, process, depth));
                index = end + 1;
            } else {
                Matcher nameMatcher = NAME.matcher(text);
                nameMatcher.region(index + 1, text.length());
                if (nameMatcher.lookingAt()) {
                    String name = nameMatcher.group();
                    String value = resolve(name, dotenv, process);
                    result.append(value == null ? "" : value);
                    index = nameMatcher.end();
                } else {
                    result.append('$');
                    index++;
                }
            }
        }
        return result.toString();
    }

    private static int expressionEnd(String text, int start) {
        int nesting = 1;
        for (int index = start; index < text.length(); index++) {
            if (text.charAt(index) == '$' && index + 1 < text.length()) {
                if (text.charAt(index + 1) == '$') {
                    index++;
                    if (index + 1 < text.length() && text.charAt(index + 1) == '{') {
                        int literalEnd = text.indexOf('}', index + 2);
                        if (literalEnd >= 0) index = literalEnd;
                    }
                } else if (text.charAt(index + 1) == '{') {
                    nesting++;
                    index++;
                }
            } else if (text.charAt(index) == '}' && --nesting == 0) return index;
        }
        return -1;
    }

    private static String expandBraced(String expression, String source, Map<String, String> dotenv,
                                       Map<String, String> process, int depth) {
        Matcher matcher = EXPRESSION.matcher(expression);
        if (!matcher.matches()) return source;
        String name = matcher.group(1);
        String operator = matcher.group(2);
        String fallback = matcher.group(3);
        String value = resolve(name, dotenv, process);
        if (operator == null) return value == null ? "" : value;
        return switch (operator) {
            case ":-" -> isUnsetOrEmpty(value) ? expand(fallback, dotenv, process, depth + 1) : value;
            case "-" -> value == null ? expand(fallback, dotenv, process, depth + 1) : value;
            case ":?" -> isUnsetOrEmpty(value) ? required(name, fallback) : value;
            case "?" -> value == null ? required(name, fallback) : value;
            case ":+" -> isUnsetOrEmpty(value) ? "" : expand(fallback, dotenv, process, depth + 1);
            case "+" -> value == null ? "" : expand(fallback, dotenv, process, depth + 1);
            default -> throw new IllegalArgumentException("Unsupported variable interpolation operator: " + operator);
        };
    }

    private static String resolve(String name, Map<String, String> dotenv, Map<String, String> process) {
        return process.containsKey(name) ? process.get(name) : dotenv.get(name);
    }

    private static boolean isUnsetOrEmpty(String value) { return value == null || value.isEmpty(); }

    private static String required(String name, String message) {
        throw new IllegalArgumentException("Variable '" + name + "' is required"
                + (message == null || message.isBlank() ? "" : ": " + message));
    }
}
