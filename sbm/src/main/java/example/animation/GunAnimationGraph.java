package example.animation;

import com.maydaymemory.mae.basic.Pose;

public interface GunAnimationGraph {
    void notifyDraw();
    void notifyTrigger();
    void tick();
    Pose getPose();
}
