package com.github.mcmodderanchor.simplebedrockmodel.v1.resource;

import com.github.mcmodderanchor.simplebedrockmodel.SimpleBedrockModel;
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.animation.BedrockAnimation;
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockModel;
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.resource.pojo.BedrockAnimationFile;
import com.google.common.collect.Maps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.UnmodifiableView;

import javax.annotation.ParametersAreNonnullByDefault;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class BedrockAnimationResourceSet extends SimplePreparableReloadListener<Map<ResourceLocation, BedrockAnimationFile>> {
    private final Map<ResourceLocation, BedrockAnimationResourceProcessor> processors;
    private final List<Consumer<Map<ResourceLocation, List<BedrockAnimation>>>> listeners;
    private final Map<ResourceLocation, List<BedrockAnimation>> animationCache;

    static BedrockAnimationResourceSet INSTANCE;

    public static BedrockAnimationResourceSet getInstance() {
        return INSTANCE;
    }

    BedrockAnimationResourceSet(Map<ResourceLocation, BedrockAnimationResourceProcessor> processors,
                                List<Consumer<Map<ResourceLocation, List<BedrockAnimation>>>> listeners) {
        this.processors = processors;
        this.listeners = listeners;
        this.animationCache = Maps.newHashMap();
    }

    @Override
    @NotNull
    @ParametersAreNonnullByDefault
    protected Map<ResourceLocation, BedrockAnimationFile> prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
        Map<ResourceLocation, BedrockAnimationFile> pojoMap = new HashMap<>();
        processors.forEach((location, processor) -> {
            // 将 ID 转换成实际动画文件路径： <namespace>:animations/<path>.json
            ResourceLocation path = ResourceLocation.fromNamespaceAndPath(location.getNamespace(), "animations/" + location.getPath() + ".json");
            resourceManager.getResource(path).ifPresentOrElse(resource -> {
                try (InputStream stream = resource.open()) {
                    BedrockAnimationFile pojo = processor.rawLoader().load(stream, BedrockAnimationFile.class);
                    if (pojo != null) {
                        pojoMap.put(location, pojo);
                    }
                }catch (IOException e) {
                    SimpleBedrockModel.LOGGER.error("Failed to load animation file: {}", path, e);
                }
            }, () -> SimpleBedrockModel.LOGGER.error("Not found animation file: {}", path));
        });
        return pojoMap;
    }

    @Override
    @ParametersAreNonnullByDefault
    protected void apply(Map<ResourceLocation, BedrockAnimationFile> pojoMap, ResourceManager pResourceManager, ProfilerFiller pProfiler) {
        animationCache.clear();
        processors.forEach((location, processor) -> {
            BedrockAnimationFile pojo = pojoMap.get(location);
            if (pojo == null) {
                return;
            }
            ResourceLocation modelKey = processor.modelKey();
            BedrockModel model = modelKey == null ? null : BedrockModelResourceSet.getInstance().getModel(modelKey);
            List<BedrockAnimation> animations = processor.converter().apply(pojo, model);
            if (animations != null) {
                animationCache.put(location, animations);
            }
        });
        // 通知所有监听重载的 listener
        Map<ResourceLocation, List<BedrockAnimation>> animationMap = getAllAnimations();
        for (Consumer<Map<ResourceLocation, List<BedrockAnimation>>> listener : listeners) {
            listener.accept(animationMap);
        }
    }

    public List<BedrockAnimation> getAnimations(ResourceLocation location) {
        return animationCache.get(location);
    }

    @UnmodifiableView
    public Map<ResourceLocation, List<BedrockAnimation>> getAllAnimations() {
        return Collections.unmodifiableMap(animationCache);
    }
}
