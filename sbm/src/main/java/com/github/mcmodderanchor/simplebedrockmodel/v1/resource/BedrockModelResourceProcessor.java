package com.github.mcmodderanchor.simplebedrockmodel.v1.resource;

import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockModel;
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.resource.pojo.BedrockModelPOJO;

import java.util.function.Function;

public record BedrockModelResourceProcessor (RawResourceLoader rawLoader,
                                             Function<BedrockModelPOJO, BedrockModel> converter){
}
