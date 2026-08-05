package com.github.mcmodderanchor.simplebedrockmodel.v1.event;

import com.github.mcmodderanchor.simplebedrockmodel.v1.common.animation.BedrockAnimation;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.Event;
import net.neoforged.fml.event.IModBusEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class RegisterBedrockAnimationReloadListenerEvent extends Event implements IModBusEvent {
    private final List<Consumer<Map<ResourceLocation, List<BedrockAnimation>>>> listeners = new ArrayList<>();

    public void register(Consumer<Map<ResourceLocation, List<BedrockAnimation>>> listener) {
        this.listeners.add(listener);
    }

    public List<Consumer<Map<ResourceLocation, List<BedrockAnimation>>>> getListeners() {
        return listeners;
    }
}
