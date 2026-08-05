package com.github.mcmodderanchor.simplebedrockmodel.v1.common.resource.exclusion;

import com.google.gson.ExclusionStrategy;
import com.google.gson.FieldAttributes;

public class ServerNormalExclusionStrategy implements ExclusionStrategy {
    @Override
    public boolean shouldSkipField(FieldAttributes f) {
        return f.getAnnotation(ClientOnly.class) != null || f.getAnnotation(NeedForRootMotion.class) != null;
    }

    @Override
    public boolean shouldSkipClass(Class<?> clazz) {
        return false;
    }
}
