package com.github.mcmodderanchor.simplebedrockmodel.v1.event;

import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockModel;
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.resource.pojo.BedrockModelPOJO;
import com.github.mcmodderanchor.simplebedrockmodel.v1.resource.BedrockModelResourceProcessor;
import com.github.mcmodderanchor.simplebedrockmodel.v1.resource.RawResourceLoader;
import com.google.common.collect.Maps;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.Event;
import net.neoforged.fml.event.IModBusEvent;

import java.util.Map;
import java.util.function.Function;

public class RegisterBedrockModelEvent extends Event implements IModBusEvent {
    private final Map<ResourceLocation, BedrockModelResourceProcessor> modelRegistry;
    private final Dist dist;

    public RegisterBedrockModelEvent(Dist dist) {
        this.modelRegistry = Maps.newHashMap();
        this.dist = dist;
    }

    public void register(ResourceLocation modelLocation,
                         RawResourceLoader loader,
                         Function<BedrockModelPOJO, BedrockModel> converter) {
        modelRegistry.put(modelLocation, new BedrockModelResourceProcessor(loader, converter));
    }

    public void register(ResourceLocation modelLocation,
                         RawResourceLoader loader) {
        register(modelLocation, loader, BedrockModel::new);
    }

    public Dist getDist() {
        return dist;
    }

    public Map<ResourceLocation, BedrockModelResourceProcessor> getModelRegistry() {
        return modelRegistry;
    }
}
