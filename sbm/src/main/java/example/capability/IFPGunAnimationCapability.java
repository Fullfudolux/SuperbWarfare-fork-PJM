package example.capability;

import example.animation.FPGunAnimationInstance;

public interface IFPGunAnimationCapability {
    FPGunAnimationInstance getAnimationInstance();

    /**
     * 获取背包上一个 tick 选中的 index。用于监听变换，更新 AnimationGraph 和播放 Draw 动画
     *
     * @return 上个 tick 选中的 index。
     */
    int getLastSelected();

    /**
     * 更新背包选中的 index。用于监听变换，更新 AnimationGraph 和播放 Draw 动画
     *
     * @param selected 选中的 index。
     */
    void setLastSelected(int selected);
}
