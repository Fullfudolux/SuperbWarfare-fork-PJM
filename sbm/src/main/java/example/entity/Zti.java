package example.entity;

import example.animation.ZtiAnimationInstance;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.*;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.NotNull;

public class Zti extends PathfinderMob {
    private static final EntityDataAccessor<Integer> ATTACK_ANIMATION_TICKS = SynchedEntityData.defineId(Zti.class, EntityDataSerializers.INT);

    private ZtiAnimationInstance animationInstance;

    public Zti(EntityType<? extends PathfinderMob> entityType, Level level) {
        super(entityType, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 80.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.25D)
                .add(Attributes.ATTACK_DAMAGE, 8.0D)
                .add(Attributes.FOLLOW_RANGE, 24.0D)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0D)
                .add(Attributes.ARMOR, 20.0D);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.@NotNull Builder builder) {
        super.defineSynchedData(builder);
        builder.define(ATTACK_ANIMATION_TICKS, 0);
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(1, new MeleeAttackGoal(this, 1.0D, false));
        goalSelector.addGoal(2, new RandomStrollGoal(this, 0.8D));
        goalSelector.addGoal(3, new LookAtPlayerGoal(this, Player.class, 8.0F));
        goalSelector.addGoal(4, new RandomLookAroundGoal(this));

        targetSelector.addGoal(0, new HurtByTargetGoal(this));
        targetSelector.addGoal(1, new NearestAttackableTargetGoal<>(this, IronGolem.class, true));
    }

    @Override
    public void tick() {
        super.tick();
        if (!level().isClientSide && isAttackAnimationActive()) {
            entityData.set(ATTACK_ANIMATION_TICKS, entityData.get(ATTACK_ANIMATION_TICKS) - 1);
        }
    }

    @Override
    public boolean doHurtTarget(@NotNull Entity target) {
        entityData.set(ATTACK_ANIMATION_TICKS, 10);
        return super.doHurtTarget(target);
    }

    public boolean isAttackAnimationActive() {
        return entityData.get(ATTACK_ANIMATION_TICKS) > 0;
    }

    @OnlyIn(Dist.CLIENT)
    public ZtiAnimationInstance getAnimationInstance() {
        if (animationInstance == null) {
            animationInstance = new ZtiAnimationInstance(this);
        }
        return animationInstance;
    }
}
