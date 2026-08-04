package com.github.mcmodderanchor.simplebedrockmodel.v1.client.event;


import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;

/**
 * 当第一人称视角触发摇晃时，玩家手部的摇晃
 */
public class RenderItemInHandBobEvent extends Event {
    public static class BobHurt extends RenderItemInHandBobEvent implements ICancellableEvent {
        public BobHurt() {
        }
    }

    public static class BobView extends RenderItemInHandBobEvent implements ICancellableEvent {
        public BobView() {
        }
    }
}
