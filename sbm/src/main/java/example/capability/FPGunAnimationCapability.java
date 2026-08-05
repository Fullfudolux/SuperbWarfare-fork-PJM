package example.capability;

import example.animation.FPGunAnimationInstance;
import example.init.ExampleModRegister;
import net.minecraft.world.entity.player.Player;

public class FPGunAnimationCapability implements IFPGunAnimationCapability{
    private FPGunAnimationInstance animationInstance;
    private int lastSelected = -1;

    public FPGunAnimationCapability() {
    }

    public void setPlayer(Player player) {
        if (this.animationInstance == null) {
            this.animationInstance = new FPGunAnimationInstance(player);
        }
    }

    @Override
    public FPGunAnimationInstance getAnimationInstance() {
        return animationInstance;
    }

    public int getLastSelected() {
        return lastSelected;
    }

    public void setLastSelected(int lastSelected) {
        this.lastSelected = lastSelected;
    }

    public static FPGunAnimationCapability get(Player player) {
        var data = player.getData(ExampleModRegister.FP_GUN_ANIMATION);
        if (!data.inited()) {
            data.setPlayer(player);
        }
        return data;
    }

    private boolean inited() {
        return animationInstance != null;
    }
}
