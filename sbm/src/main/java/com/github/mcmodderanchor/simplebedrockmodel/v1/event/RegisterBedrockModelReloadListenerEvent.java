package com.github.mcmodderanchor.simplebedrockmodel.v1.event;

import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockModel;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.Event;
import net.neoforged.fml.event.IModBusEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class RegisterBedrockModelReloadListenerEvent extends Event implements IModBusEvent {
    private final List<Consumer<Map<ResourceLocation, BedrockModel>>> listeners = new ArrayList<>();

    public void register(Consumer<Map<ResourceLocation, BedrockModel>> listener) {
        this.listeners.add(listener);
    }

    public List<Consumer<Map<ResourceLocation, BedrockModel>>> getListeners() {
        return listeners;
    }
}
