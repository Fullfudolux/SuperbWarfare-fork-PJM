package com.atsuishio.superbwarfare.entity.vehicle

import com.atsuishio.superbwarfare.entity.projectile.FastThrowableProjectile
import com.atsuishio.superbwarfare.entity.projectile.MediumRocketEntity
import com.atsuishio.superbwarfare.entity.projectile.MissileProjectile
import com.atsuishio.superbwarfare.entity.projectile.RpgRocketStandardEntity
import com.atsuishio.superbwarfare.entity.projectile.RpgRocketTBGEntity
import com.atsuishio.superbwarfare.entity.projectile.SmallRocketEntity
import com.atsuishio.superbwarfare.entity.getValue
import com.atsuishio.superbwarfare.entity.setValue
import com.atsuishio.superbwarfare.init.ModItems
import com.atsuishio.superbwarfare.init.ModSounds
import com.atsuishio.superbwarfare.tools.InventoryTool
import com.atsuishio.superbwarfare.tools.ParticleTool
import net.minecraft.ChatFormatting
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4d
import org.joml.Vector3f
import org.joml.Vector4d
import kotlin.math.abs
import kotlin.math.atan2

// PJM: M10 Booker с КАЗ — та же машина плюс комплекс активной защиты.
class M10BookerApsEntity(type: EntityType<out M10BookerApsEntity>, world: Level) : M10BookerEntity(type, world) {

    // ---- КАЗ (APS, Trophy-подобный) ----------------------------------------
    // Кость APS на башне: два блока пусковых APSR/APSL. Жёсткий перехват
    // подлетающих ракет; кинетические снаряды и пули не перехватываются,
    // как и в реальности. Зенит башни не прикрыт (top-attack проходит).

    private var apsReloadCooldown = APS_RELOAD_TICKS
    private var apsFireCooldown = 0
    private var apsNoAmmo = false

    /** Заряженные перехватчики каждого борта. Синхронизируются ради HUD. */
    var apsChargesRight by APS_CHARGES_RIGHT
    var apsChargesLeft by APS_CHARGES_LEFT

    /** Прогресс текущей перезарядки, 0..1. 1 — боекомплект полон. Синхронизируется ради HUD. */
    var apsReloadProgress by APS_RELOAD_PROGRESS

    /** Азимуты правой и левой пусковых относительно башни, градусы. Синхронизируются ради рендера. */
    var apsAimYawRight by APS_AIM_YAW_RIGHT
    var apsAimYawLeft by APS_AIM_YAW_LEFT

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        super.defineSynchedData(builder)
        builder.define(APS_AIM_YAW_RIGHT, 0f)
            .define(APS_AIM_YAW_LEFT, 0f)
            .define(APS_CHARGES_RIGHT, 0)
            .define(APS_CHARGES_LEFT, 0)
            .define(APS_RELOAD_PROGRESS, 1f)
    }

    override fun baseTick() {
        super.baseTick()
        if (level().isClientSide || isRemoved) return
        tickAps()
    }

    override fun addAdditionalSaveData(compound: CompoundTag) {
        super.addAdditionalSaveData(compound)
        compound.putInt("ApsChargesRight", apsChargesRight)
        compound.putInt("ApsChargesLeft", apsChargesLeft)
    }

    override fun readAdditionalSaveData(compound: CompoundTag) {
        super.readAdditionalSaveData(compound)
        if (compound.contains("ApsChargesRight")) apsChargesRight = compound.getInt("ApsChargesRight")
        if (compound.contains("ApsChargesLeft")) apsChargesLeft = compound.getInt("ApsChargesLeft")
    }

    private fun tickAps() {
        // КАЗ питается от бортовой сети — работает только на заведённой машине
        if (isWreck || turretDamaged || health <= 0 || !engineReady()) return

        if (apsFireCooldown > 0) apsFireCooldown--

        // боекомплект набивается целиком: на каждом борту по 2 перехватчика
        val missing = (APS_SIDE_CHARGES - apsChargesRight) + (APS_SIDE_CHARGES - apsChargesLeft)
        if (missing > 0 && --apsReloadCooldown <= 0) {
            var loaded = InventoryTool.consumeItem(inventory, ModItems.APS_INTERCEPTOR.get(), missing)
            if (loaded > 0) {
                // добиваем борта поровну, начиная с более пустого
                while (loaded > 0 && (apsChargesRight < APS_SIDE_CHARGES || apsChargesLeft < APS_SIDE_CHARGES)) {
                    if (apsChargesRight <= apsChargesLeft && apsChargesRight < APS_SIDE_CHARGES) {
                        apsChargesRight++
                    } else {
                        apsChargesLeft++
                    }
                    loaded--
                }
                apsReloadCooldown = APS_RELOAD_TICKS
                apsNoAmmo = false
                level().playSound(null, this, ModSounds.APS_RELOAD.get(), soundSource, 0.7f, 1f)
            } else {
                // перехватчиков в инвентаре нет — пробуем снова через секунду
                apsReloadCooldown = 20
                apsNoAmmo = true
            }
        }

        apsReloadProgress = when {
            missing <= 0 -> 1f
            apsNoAmmo -> 0f
            else -> 1f - apsReloadCooldown.toFloat() / APS_RELOAD_TICKS
        }

        if (apsChargesRight <= 0 && apsChargesLeft <= 0) return

        val threat = level().getEntities(this, boundingBox.inflate(APS_RANGE)) { it.isApsThreat() }
            .minByOrNull { it.distanceToSqr(this) } as FastThrowableProjectile? ?: return

        // угрозу отрабатывает пусковая своего борта: правая — правый сектор, левая — левый.
        // Пустой борт свой сектор не прикрывает — вторая пусковая туда не дотягивается.
        // Без цели пусковая остаётся там, где довернулась, в исходное не откатывается.
        val target = turretLocalYaw(threat.position())
        val right = target < 0f
        if ((if (right) apsChargesRight else apsChargesLeft) <= 0) return
        val aim = if (right) apsAimYawRight else apsAimYawLeft
        val slewed = aim + Mth.clamp(Mth.wrapDegrees(target - aim), -APS_SLEW_RATE, APS_SLEW_RATE)
        if (right) apsAimYawRight = slewed else apsAimYawLeft = slewed

        // пока пусковая не довернулась — не стреляем
        if (abs(Mth.wrapDegrees(target - slewed)) > APS_AIM_TOLERANCE) return
        if (apsFireCooldown <= 0) intercept(threat, right)
    }

    /** Азимут точки в системе координат башни, градусы. */
    private fun turretLocalYaw(worldPos: Vec3): Float {
        val local = Matrix4d(getTurretTransform(1f)).invert()
            .transform(Vector4d(worldPos.x, worldPos.y, worldPos.z, 1.0))
        return Mth.wrapDegrees(Math.toDegrees(atan2(local.x, local.z)).toFloat())
    }

    private fun Entity.isApsThreat(): Boolean {
        if (this !is FastThrowableProjectile) return false
        if (this !is MissileProjectile && this !is RpgRocketStandardEntity && this !is RpgRocketTBGEntity
            && this !is MediumRocketEntity && this !is SmallRocketEntity
        ) return false

        val owner = this.owner
        if (owner === this@M10BookerApsEntity || owner?.vehicle === this@M10BookerApsEntity) return false

        val toVehicle = this@M10BookerApsEntity.position().add(0.0, 1.0, 0.0).subtract(position())
        val distance = toVehicle.length()
        if (distance < APS_MIN_DISTANCE || distance > APS_RANGE) return false

        val motion = deltaMovement
        if (motion.length() < 0.1) return false
        // подлетает ли к нам вообще
        if (motion.normalize().dot(toVehicle.scale(1.0 / distance)) < 0.6) return false
        // мёртвая зона сверху: пикирующие боеприпасы КАЗ не берёт
        return motion.normalize().y > -APS_TOP_ATTACK_COS
    }

    /** След перехватчика: перехват мгновенный, поэтому трассу рисуем сразу целиком. */
    private fun spawnTrail(level: ServerLevel, from: Vec3, to: Vec3) {
        val steps = (from.distanceTo(to) / APS_TRAIL_STEP).toInt().coerceIn(1, 64)
        for (i in 0..steps) {
            val p = from.lerp(to, i.toDouble() / steps)
            ParticleTool.sendParticle(level, APS_TRAIL_DUST, p.x, p.y, p.z, 1, 0.02, 0.02, 0.02, 0.0, true)
        }
    }

    private fun intercept(threat: FastThrowableProjectile, right: Boolean) {
        val level = level() as? ServerLevel ?: return

        val pod = if (right) APS_POD_RIGHT else APS_POD_LEFT
        val launcher = transformPosition(getTurretTransform(1f), pod.x, pod.y, pod.z)
            .let { Vec3(it.x, it.y, it.z) }

        val hitPos = threat.position()
        val direct = hitPos.subtract(launcher).normalize()

        ParticleTool.spawnDirectionalParticles(6, 0.15, level, ParticleTypes.LARGE_SMOKE, direct, launcher, 0.4)
        ParticleTool.sendParticle(level, ParticleTypes.FLASH, launcher.x, launcher.y, launcher.z, 1, 0.0, 0.0, 0.0, 0.0, true)
        spawnTrail(level, launcher, hitPos)
        level.playSound(null, this, ModSounds.APS_FIRE.get(), soundSource, 2f, 1.2f)

        // кумулятивная струя разрушена — подрыв ослаблен и происходит в стороне от брони
        threat.setExplosionDamage(threat.getExplosionDamage() * APS_DAMAGE_LEFT)
        threat.setExplosionRadius(threat.getExplosionRadius() * APS_DAMAGE_LEFT)
        ParticleTool.spawnSmallExplosionParticles(level, hitPos)
        threat.causeExplode(hitPos)
        threat.discard()

        if (right) apsChargesRight-- else apsChargesLeft--
        apsFireCooldown = APS_FIRE_COOLDOWN
        if (apsReloadCooldown <= 0) apsReloadCooldown = APS_RELOAD_TICKS

        (firstPassenger as? Player)?.displayClientMessage(
            Component.translatable(
                "tips.superbwarfare.m_10_booker_aps.intercepted", apsChargesLeft, apsChargesRight
            )
                .withStyle(ChatFormatting.GOLD),
            true
        )
    }

    companion object {
        @JvmField
        val APS_AIM_YAW_RIGHT: EntityDataAccessor<Float> =
            SynchedEntityData.defineId(M10BookerApsEntity::class.java, EntityDataSerializers.FLOAT)

        @JvmField
        val APS_AIM_YAW_LEFT: EntityDataAccessor<Float> =
            SynchedEntityData.defineId(M10BookerApsEntity::class.java, EntityDataSerializers.FLOAT)

        @JvmField
        val APS_CHARGES_RIGHT: EntityDataAccessor<Int> =
            SynchedEntityData.defineId(M10BookerApsEntity::class.java, EntityDataSerializers.INT)

        @JvmField
        val APS_CHARGES_LEFT: EntityDataAccessor<Int> =
            SynchedEntityData.defineId(M10BookerApsEntity::class.java, EntityDataSerializers.INT)

        @JvmField
        val APS_RELOAD_PROGRESS: EntityDataAccessor<Float> =
            SynchedEntityData.defineId(M10BookerApsEntity::class.java, EntityDataSerializers.FLOAT)

        const val APS_SIDE_CHARGES = 2                // перехватчиков на борт
        private const val APS_SLEW_RATE = 30f         // град/тик доворота пусковых
        private const val APS_AIM_TOLERANCE = 20f     // допуск наведения перед пуском
        private const val APS_TRAIL_STEP = 0.25       // шаг частиц трассы, блоки

        // трасса перехватчика
        private val APS_TRAIL_DUST = DustParticleOptions(Vector3f(1f, 0.55f, 0.15f), 0.8f)
        private const val APS_RELOAD_TICKS = 600      // 30 с на восполнение одного перехватчика
        private const val APS_FIRE_COOLDOWN = 10      // задержка между пусками
        private const val APS_RANGE = 12.0            // радиус обнаружения
        private const val APS_MIN_DISTANCE = 3.5      // ближе перехватывать уже поздно
        private const val APS_TOP_ATTACK_COS = 0.85f  // ~58° от горизонта — мёртвая зона сверху
        private const val APS_DAMAGE_LEFT = 0.25f     // остаточный эффект подорванной БЧ

        // Пивоты APSR/APSL из m_10_booker.geo.json, переведённые в локальные
        // координаты башни (X инвертирован из-за root-180, как у BarrelPos).
        private val APS_POD_RIGHT = Vec3(-1.039, 0.701, -1.465)
        private val APS_POD_LEFT = Vec3(1.219, 0.659, -1.457)
    }
}
