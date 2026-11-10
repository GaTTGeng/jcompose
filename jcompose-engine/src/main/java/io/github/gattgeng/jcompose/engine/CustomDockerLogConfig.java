package io.github.gattgeng.jcompose.engine;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.github.dockerjava.api.model.LogConfig;

import java.io.IOException;
import java.util.Map;

/** Docker Engine logging configuration for plugin drivers absent from docker-java's enum. */
@JsonSerialize(using = CustomDockerLogConfig.Serializer.class)
final class CustomDockerLogConfig extends LogConfig {
    private final String driverName;

    CustomDockerLogConfig(String driverName, Map<String, String> options) {
        this.driverName = driverName;
        setConfig(options);
    }

    String driverName() {
        return driverName;
    }

    static final class Serializer extends JsonSerializer<CustomDockerLogConfig> {
        @Override
        public void serialize(CustomDockerLogConfig value, JsonGenerator generator,
                              SerializerProvider serializers) throws IOException {
            generator.writeStartObject();
            generator.writeStringField("Type", value.driverName());
            generator.writeObjectField("Config", value.getConfig());
            generator.writeEndObject();
        }
    }
}
