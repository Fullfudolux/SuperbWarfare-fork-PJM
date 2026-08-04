package example.client.event;

import example.animation.FPGunAnimationInstance;
import example.animation.GunAnimationGraph;
import example.capability.FPGunAnimationCapability;
import example.init.ExampleModRegister;
import example.item.GunItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;


@EventBusSubscriber(value = Dist.CLIENT)
public class ClientTicker {
    @SubscribeEvent
    public static void onRenderTick(RenderFrameEvent.Pre event) {
        Player player = Minecraft.getInstance().player;
        if (player != null) {
            var cap = player.getData(ExampleModRegister.FP_GUN_ANIMATION);
            cap.setPlayer(player);
            GunAnimationGraph animationGraph = cap.getAnimationInstance().getAnimationGraph();
            if (animationGraph != null) {
                animationGraph.tick();
            }
        }
    }

    private static int oldHotBarSelected = -1;

    @SubscribeEvent
    public static void onPlayerChangeSelect(ClientTickEvent.Pre event) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        Inventory inventory = player.getInventory();
        // 这里就先简单地判断有没有切换选中的格子，用于测试。
        if (oldHotBarSelected != inventory.selected) {
            ItemStack selected = inventory.getSelected();
            var cap = FPGunAnimationCapability.get(player);
            if (selected.getItem() instanceof GunItem gunItem) {
                FPGunAnimationInstance animationInstance = cap.getAnimationInstance();
                animationInstance.updateAnimationGraphAndDraw(gunItem.getAnimationGraph(animationInstance));
            } else {
                cap.getAnimationInstance().updateAnimationGraphAndDraw(null);
            }
            oldHotBarSelected = inventory.selected;
        }
    }
}
