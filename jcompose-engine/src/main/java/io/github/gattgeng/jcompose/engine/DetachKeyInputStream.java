package io.github.gattgeng.jcompose.engine;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Filters a Docker detach-key sequence from forwarded standard input. */
final class DetachKeyInputStream extends InputStream {
    private final InputStream source;
    private final byte[] detachKeys;
    private final Runnable onDetach;
    private final byte[] pending;
    private int pendingCount;
    private boolean detached;
    private boolean eof;

    DetachKeyInputStream(InputStream source, String detachKeys, Runnable onDetach) {
        this.source = Objects.requireNonNull(source, "source");
        this.detachKeys = parse(detachKeys);
        this.onDetach = Objects.requireNonNull(onDetach, "onDetach");
        this.pending = new byte[this.detachKeys.length];
    }

    @Override
    public int read() throws IOException {
        if (detached) return -1;
        if (detachKeys.length == 0) return source.read();
        while (true) {
            if (pendingCount == detachKeys.length && matches()) {
                detached = true;
                onDetach.run();
                return -1;
            }

            if (eof) return pendingCount == 0 ? -1 : removeFirst();
            int value = source.read();
            if (value < 0) {
                eof = true;
                continue;
            }
            pending[pendingCount++] = (byte) value;
            if (!isPrefix()) return removeFirst();
        }
    }

    @Override
    public void close() {
        // System.in is process-owned; closing this wrapper must not close it.
    }

    static byte[] parse(String value) {
        Objects.requireNonNull(value, "detachKeys");
        if (value.isEmpty()) return new byte[0];
        List<Byte> keys = new ArrayList<>();
        for (String key : value.split(",", -1)) {
            if (key.length() == 1 && key.charAt(0) >= 0x20 && key.charAt(0) <= 0x7e) {
                keys.add((byte) key.charAt(0));
            } else if (key.startsWith("ctrl-") && key.length() == 6) {
                char control = key.charAt(5);
                if (control >= 'a' && control <= 'z') keys.add((byte) (control - 'a' + 1));
                else if (control == '@') keys.add((byte) 0);
                else if (control == '[') keys.add((byte) 27);
                else if (control == '\\') keys.add((byte) 28);
                else if (control == ']') keys.add((byte) 29);
                else if (control == '^') keys.add((byte) 30);
                else if (control == '_') keys.add((byte) 31);
                else throw new IllegalArgumentException("Invalid Docker detach key: " + key);
            } else {
                throw new IllegalArgumentException("Invalid Docker detach key: " + key);
            }
        }
        byte[] result = new byte[keys.size()];
        for (int i = 0; i < result.length; i++) result[i] = keys.get(i);
        return result;
    }

    private boolean matches() {
        for (int i = 0; i < detachKeys.length; i++)
            if (pending[i] != detachKeys[i]) return false;
        return true;
    }

    private boolean isPrefix() {
        if (pendingCount > detachKeys.length) return false;
        for (int i = 0; i < pendingCount; i++)
            if (pending[i] != detachKeys[i]) return false;
        return true;
    }

    private int removeFirst() {
        int result = pending[0] & 0xff;
        System.arraycopy(pending, 1, pending, 0, --pendingCount);
        return result;
    }
}
