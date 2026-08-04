package com.github.mcmodderanchor.simplebedrockmodel;

import com.github.mcmodderanchor.simplebedrockmodel.v1.network.NetworkHandler;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


@Mod(SimpleBedrockModel.MOD_ID)
public class SimpleBedrockModel {
    public static final String MOD_ID = "simplebedrockmodel";
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);

    public SimpleBedrockModel(IEventBus modEventBus) {
        modEventBus.addListener(NetworkHandler::register);
    }

    public static ResourceLocation modLoc(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
