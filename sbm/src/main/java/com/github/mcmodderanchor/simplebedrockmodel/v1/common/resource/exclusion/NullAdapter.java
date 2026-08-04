package com.github.mcmodderanchor.simplebedrockmodel.v1.common.resource.exclusion;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;

public class NullAdapter<T> extends TypeAdapter<T> {
    @Override
    public void write(JsonWriter out, T value) throws IOException {
        out.nullValue(); // 序列化时直接输出 null
    }

    @Override
    public T read(JsonReader in) throws IOException {
        // 跳过整个 JSON 值，然后返回 null
        in.skipValue();
        return null;
    }
}
