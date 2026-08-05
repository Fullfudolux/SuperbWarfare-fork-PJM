package example.animation;

import example.item.GunItem;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

public class FPGunAnimationInstance {
    private final Player player;
    private GunAnimationGraph animationGraph;
    private boolean isRaisingGun;
    private boolean isCooling;

    public FPGunAnimationInstance(Player player) {
        this.player = player;
    }

    /**
     * 这个方法通常在玩家切换物品时调用。
     *
     * @param animationGraph 如果是 {@link example.item.GunItem GunItem}，
     *                       这个参数填 {@link example.item.GunItem#getAnimationGraph(FPGunAnimationInstance) GunItem.getAnimationGraph()}，
     *                       否则填 null 即可。
     */
    public void updateAnimationGraphAndDraw(@Nullable GunAnimationGraph animationGraph) {
        // 更新 animationGraph 然后通知 animationGraph 开始 draw 动画
        this.animationGraph = animationGraph;
        if (animationGraph != null) {
            animationGraph.notifyDraw();
        }
    }

    /**
     * 这个方法在开火时调用。
     */
    public void trigger() {
        if (animationGraph != null) {
            animationGraph.notifyTrigger();
        }
    }

    public int getAmmoInMag() {
        ItemStack selected = player.getInventory().getSelected();
        if (selected.getItem() instanceof GunItem gunItem) {
            if (gunItem.hasMagInstalled(selected)) {
                return gunItem.getAmmoInMag(selected);
            } else {
                return 0;
            }
        }
        return 0;
    }

    public int getAmmoInGun() {
        ItemStack selected = player.getInventory().getSelected();
        if (selected.getItem() instanceof GunItem gunItem) {
            return gunItem.getAmmoInGun(selected);
        }
        return 0;
    }

    public boolean hasMagInstalled() {
        ItemStack selected = player.getInventory().getSelected();
        if (selected.getItem() instanceof GunItem gunItem) {
            return gunItem.hasMagInstalled(selected);
        }
        return false;
    }

    public void setAmmoInGun(int ammoInGun) {
        ItemStack selected = player.getInventory().getSelected();
        if (selected.getItem() instanceof GunItem gunItem) {
            gunItem.setAmmoInGun(selected, ammoInGun);
        }
    }

    public void setAmmoInMag(int ammoInMag) {
        ItemStack selected = player.getInventory().getSelected();
        if (selected.getItem() instanceof GunItem gunItem) {
            gunItem.setAmmoInMag(selected, ammoInMag);
        }
    }

    public void setMagInstalled(boolean installed) {
        ItemStack selected = player.getInventory().getSelected();
        if (selected.getItem() instanceof GunItem gunItem) {
            gunItem.setMagInstalled(selected, installed);
        }
    }

    public GunAnimationGraph getAnimationGraph() {
        return animationGraph;
    }

    public boolean isRaisingGun() {
        return isRaisingGun;
    }

    public void setRaisingGun(boolean raisingGun) {
        isRaisingGun = raisingGun;
    }

    public boolean isCooling() {
        return isCooling;
    }

    public void setCooling(boolean cooling) {
        isCooling = cooling;
    }

    public Player getPlayer() {
        return player;
    }
}
