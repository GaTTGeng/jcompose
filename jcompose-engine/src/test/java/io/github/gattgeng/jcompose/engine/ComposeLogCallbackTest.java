package io.github.gattgeng.jcompose.engine;

import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import io.github.gattgeng.jcompose.api.ILogConsumer;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ComposeLogCallbackTest {
    @Test
    void interleavedFramesKeepStreamIdentityAndPerStreamLineOrder() {
        List<String> lines = new ArrayList<>();
        var callback = callback(lines);
        callback.onNext(frame(StreamType.STDOUT, "out-"));
        callback.onNext(frame(StreamType.STDERR, "err-one\r\nerr-"));
        callback.onNext(frame(StreamType.STDOUT, "one\nout-two\n"));
        callback.onNext(frame(StreamType.STDERR, "two\n"));
        callback.onComplete();

        assertEquals(List.of("app:stderr:err-one", "app:stdout:out-one", "app:stdout:out-two",
                "app:stderr:err-two", "complete:app"), lines);
    }

    @Test
    void splitUtf8CharactersAndUnterminatedLinesRemainSeparate() {
        List<String> lines = new ArrayList<>();
        var callback = callback(lines);
        byte[] stdout = "界🌍".getBytes(StandardCharsets.UTF_8);
        byte[] stderr = "错误".getBytes(StandardCharsets.UTF_8);
        for (int index = 0; index < stdout.length; index++) {
            callback.onNext(new Frame(StreamType.STDOUT, new byte[] {stdout[index]}));
            if (index < stderr.length)
                callback.onNext(new Frame(StreamType.STDERR, new byte[] {stderr[index]}));
        }
        callback.onNext(frame(StreamType.STDOUT, "\n尾"));
        callback.onComplete();

        assertEquals(List.of("app:stdout:界🌍", "app:stdout:尾", "app:stderr:错误", "complete:app"), lines);
    }

    @Test
    void unfinishedUtf8AtEndOfStreamIsReplacedOnce() {
        List<String> lines = new ArrayList<>();
        var callback = callback(lines);
        byte[] character = "界".getBytes(StandardCharsets.UTF_8);
        callback.onNext(new Frame(StreamType.STDERR, Arrays.copyOfRange(character, 0, 1)));
        callback.onNext(new Frame(StreamType.STDERR, Arrays.copyOfRange(character, 1, 2)));
        callback.onComplete();

        assertEquals(List.of("app:stderr:�", "complete:app"), lines);
    }

    private static Frame frame(StreamType stream, String text) {
        return new Frame(stream, text.getBytes(StandardCharsets.UTF_8));
    }

    private static ComposeService.LogCallback callback(List<String> lines) {
        return new ComposeService.LogCallback("app", new ILogConsumer() {
            @Override public void onLog(String serviceName, String message, boolean standardError) {
                lines.add(serviceName + ":" + (standardError ? "stderr:" : "stdout:") + message);
            }
            @Override public void onLogComplete(String serviceName) { lines.add("complete:" + serviceName); }
            @Override public void onStatus(String serviceName, String message) { }
        }, false);
    }
}
