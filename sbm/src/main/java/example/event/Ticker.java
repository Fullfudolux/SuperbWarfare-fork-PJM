package example.event;

import example.animation.FPGunAnimationInstance;
import example.animation.GunAnimationGraph;
import example.capability.FPGunAnimationCapability;
import example.item.GunItem;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

@EventBusSubscriber
public class Ticker {

    @SubscribeEvent
    public static void onServerTick(PlayerTickEvent.Pre event) {
        if (!event.getEntity().level().isClientSide()) {
            Player player = event.getEntity();
            Inventory inventory = player.getInventory();
            // 需要先更新 animationGraph 再 tick，保持逻辑严密
            var capability = FPGunAnimationCapability.get(player);
            if (capability.getLastSelected() != inventory.selected) {
                ItemStack selected = inventory.getSelected();
                if (selected.getItem() instanceof GunItem gunItem) {
                    FPGunAnimationInstance animationInstance = capability.getAnimationInstance();
                    animationInstance.updateAnimationGraphAndDraw(gunItem.getAnimationGraph(animationInstance));
                } else {
                    capability.getAnimationInstance().updateAnimationGraphAndDraw(null);
                }
                capability.setLastSelected(inventory.selected);
            }
            GunAnimationGraph animationGraph = capability.getAnimationInstance().getAnimationGraph();
            if (animationGraph != null) {
                animationGraph.tick();
            }
        }
    }
}
