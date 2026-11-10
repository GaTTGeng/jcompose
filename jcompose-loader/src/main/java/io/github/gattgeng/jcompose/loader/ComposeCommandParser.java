package io.github.gattgeng.jcompose.loader;

import java.util.ArrayList;
import java.util.List;

/** Parses Compose scalar command values into argument lists without evaluating shell expressions. */
public final class ComposeCommandParser {
    private ComposeCommandParser() { }

    /**
     * Splits a scalar command using quote and escape grouping, preserving empty quoted arguments.
     *
     * @param command Compose scalar command
     * @return immutable command arguments
     * @throws IllegalArgumentException if a quote or escape is left open
     */
    public static List<String> parse(String command) {
        List<String> arguments = new ArrayList<>();
        StringBuilder argument = new StringBuilder();
        char quote = 0;
        boolean escaped = false;
        boolean started = false;
        for (int index = 0; index < command.length(); index++) {
            char current = command.charAt(index);
            if (escaped) {
                argument.append(current);
                escaped = false;
                started = true;
            } else if (current == '\\' && quote != '\'') {
                escaped = true;
                started = true;
            } else if (quote != 0) {
                if (current == quote) quote = 0;
                else argument.append(current);
            } else if (current == '\'' || current == '"') {
                quote = current;
                started = true;
            } else if (Character.isWhitespace(current)) {
                if (started) {
                    arguments.add(argument.toString());
                    argument.setLength(0);
                    started = false;
                }
            } else {
                argument.append(current);
                started = true;
            }
        }
        if (escaped || quote != 0) throw new IllegalArgumentException("invalid command line string");
        if (started) arguments.add(argument.toString());
        return List.copyOf(arguments);
    }
}
