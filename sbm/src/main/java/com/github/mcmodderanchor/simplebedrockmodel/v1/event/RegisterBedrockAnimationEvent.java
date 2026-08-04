package com.github.mcmodderanchor.simplebedrockmodel.v1.event;

import com.github.mcmodderanchor.simplebedrockmodel.v1.common.animation.BedrockAnimation;
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockModel;
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.resource.pojo.BedrockAnimationFile;
import com.github.mcmodderanchor.simplebedrockmodel.v1.resource.BedrockAnimationResourceProcessor;
import com.github.mcmodderanchor.simplebedrockmodel.v1.resource.RawResourceLoader;
import com.google.common.collect.Maps;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.Event;
import net.neoforged.fml.event.IModBusEvent;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Used to register bedrock animations so that loaders can load them.
 */
public class RegisterBedrockAnimationEvent extends Event implements IModBusEvent {
    private final Map<ResourceLocation, BedrockAnimationResourceProcessor> animationRegistry;
    private final Dist dist;

    public RegisterBedrockAnimationEvent(Dist dist) {
        this.animationRegistry = Maps.newHashMap();
        this.dist = dist;
    }

    public void register(ResourceLocation animationLocation,
                         ResourceLocation modelLocation,
                         RawResourceLoader loader,
                         BiFunction<BedrockAnimationFile, BedrockModel, List<BedrockAnimation>> converter) {
        animationRegistry.put(animationLocation, new BedrockAnimationResourceProcessor(loader, modelLocation, converter));
    }

    public void register(ResourceLocation animationLocation,
                         ResourceLocation modelLocation,
                         RawResourceLoader loader) {
        register(animationLocation, modelLocation, loader, BedrockAnimation::createAnimation);
    }


    public Dist getDist() {
        return dist;
    }

    public Map<ResourceLocation, BedrockAnimationResourceProcessor> getAnimationRegistry() {
        return animationRegistry;
    }
}
