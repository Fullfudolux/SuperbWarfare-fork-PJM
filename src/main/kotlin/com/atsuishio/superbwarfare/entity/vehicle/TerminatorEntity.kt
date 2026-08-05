package com.atsuishio.superbwarfare.entity.vehicle

import com.atsuishio.superbwarfare.data.gun.GunProp
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.entity.vehicle.utils.VehicleVecUtils
import com.atsuishio.superbwarfare.init.ModItems
import com.atsuishio.superbwarfare.init.ModSounds
import com.atsuishio.superbwarfare.tools.OBB
import com.atsuishio.superbwarfare.tools.ParticleTool
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import java.util.*

// PJM: БМПТ-72 «Терминатор». Гусеничная машина огневой поддержки на базе Т-72:
// спаренная 30-мм 2А42, спаренный 7.62-мм ПКТМ, 4 ПТУР «Атака-Т» в необитаемой башне.
open class TerminatorEntity(type: EntityType<out TerminatorEntity>, world: Level) : VehicleEntity(type, world) {

    /** Задержка между заряжаниями ПТУР, чтобы одно удержание ПКМ не набивало всю укладку. */
    private var loadCooldown = 0

    // длительность звука запуска start_ext (t90, ~1.69 c)
    override fun engineStartupDurationTicks() = 34

    override fun baseTick() {
        super.baseTick()
        if (isRemoved) return
        if (loadCooldown > 0) loadCooldown--
        if (level().isClientSide) tickExhaust()
    }

    /**
     * Ручное заряжание ПТУР, как у [OsaEntity]: ПКМ ракетой по торцу ТПК (боксы `Part: Interactive`
     * в data-json). Боезапас — счётчик, `BoundUpWithAmmoAmount` сам сажает пуск на нужную
     * направляющую, а рендерер прячет отстрелянные ракеты.
     */
    override fun interact(player: Player, hand: InteractionHand): InteractionResult {
        // до super: VehicleEntity.interact сажает игрока в машину, поэтому перехватываем
        // ровно один случай — ракета в руке + взгляд в торец тубуса.
        if (tryLoadMissile(player)) return InteractionResult.SUCCESS
        return super.interact(player, hand)
    }

    private fun tryLoadMissile(player: Player): Boolean {
        val stack = player.mainHandItem
        if (!stack.`is`(ModItems.MEDIUM_ANTI_GROUND_MISSILE.get())) return false
        if (loadCooldown > 0 || isWreck) return false

        val looking = OBB.getLookingObb(player, player.entityInteractionRange())
        if (looking == null || looking.part != OBB.Part.INTERACTIVE) return false

        val data = getGunData(MISSILE_WEAPON) ?: return false
        if (data.ammo.get() >= data.get(GunProp.MAGAZINE)) return false

        if (level() is ServerLevel) {
            modifyGunData(MISSILE_WEAPON) { it.ammo.set(it.ammo.get() + 1) }
            if (!player.isCreative) stack.shrink(1)
            level().playSound(
                null, x, y, z, ModSounds.TYPE_63_RELOAD.get(),
                SoundSource.PLAYERS, 1f, random.nextFloat() * 0.1f + 0.9f
            )
            loadCooldown = 10
        }
        return true
    }

    /** Струйка выхлопа из левого борта кормы, пока двигатель заведён (как у [OsaEntity]). */
    private fun tickExhaust() {
        if (isWreck || !engineOn || engineStarting()) return
        val load = Math.abs(power)
        if (load < 0.05f && tickCount % 4 != 0) return

        val pos = VehicleVecUtils.transformPosition(
            getVehicleTransform(1f),
            EXHAUST_POS[0], EXHAUST_POS[1], EXHAUST_POS[2]
        )
        val spread = 0.06
        level().addParticle(
            ParticleTypes.SMOKE,
            pos.x + (random.nextDouble() - 0.5) * spread,
            pos.y + (random.nextDouble() - 0.5) * spread,
            pos.z + (random.nextDouble() - 0.5) * spread,
            deltaMovement.x * 0.5,
            0.03 + 0.12 * load,
            deltaMovement.z * 0.5
        )
    }

    override fun vehicleShoot(living: LivingEntity?, uuid: UUID?, targetPos: Vec3?) {
        val level = living?.level()
        // дульная вспышка/дым только у спаренной пушки (индекс 0), не у пулемёта и ПТУР
        if (level is ServerLevel && living == firstPassenger && getWeaponIndex(0) == 0) {
            ParticleTool.spawnMediumCannonMuzzleParticles(getShootVec(living, 1f), getShootPos(living, 1f), level, this)
        }
        super.vehicleShoot(living, uuid, targetPos)
    }

    override fun getTurretMaxHealth() = 90f

    override fun getWheelMaxHealth() = 90f

    override fun getEngineMaxHealth() = 110f

    // длина контура гусеницы в юнитах модели (см. TerminatorRenderer.TRACK_T)
    override fun getTrackAnimationLength() = 212

    companion object {
        private const val MISSILE_WEAPON = "Missile"

        /** Срез выхлопной трубы в координатах машины (кадр движка, перед = +Z). */
        private val EXHAUST_POS = doubleArrayOf(1.875, 1.269, -1.875)
    }
}
