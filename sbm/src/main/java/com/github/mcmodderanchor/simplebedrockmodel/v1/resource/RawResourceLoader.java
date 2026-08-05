package com.github.mcmodderanchor.simplebedrockmodel.v1.resource;

import java.io.InputStream;

public interface RawResourceLoader {
    <T> T load(InputStream inputStream, Class<T> clazz);
}
