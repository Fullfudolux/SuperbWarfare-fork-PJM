package example.item;

import example.animation.FPGunAnimationInstance;
import example.animation.GunAnimationGraph;
import net.minecraft.world.item.ItemStack;

public interface GunItem {
    GunAnimationGraph getAnimationGraph(FPGunAnimationInstance animationInstance);

    boolean hasMagInstalled(ItemStack itemStack);

    int getAmmoInMag(ItemStack itemStack);

    int getAmmoInGun(ItemStack itemStack);

    void setMagInstalled(ItemStack itemStack, boolean installed);

    void setAmmoInMag(ItemStack itemStack, int ammo);

    void setAmmoInGun(ItemStack itemStack, int ammo);
}
