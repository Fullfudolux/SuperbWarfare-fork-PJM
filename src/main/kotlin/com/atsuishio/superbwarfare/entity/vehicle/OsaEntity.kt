package com.atsuishio.superbwarfare.entity.vehicle

import com.atsuishio.superbwarfare.data.gun.GunProp
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.entity.vehicle.utils.VehicleVecUtils
import com.atsuishio.superbwarfare.init.ModItems
import com.atsuishio.superbwarfare.init.ModSounds
import com.atsuishio.superbwarfare.tools.OBB
import com.atsuishio.superbwarfare.tools.ParticleTool
import net.minecraft.core.particles.BlockParticleOption
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3

// PJM: 9К33 «Оса-АКМ»
open class OsaEntity(type: EntityType<OsaEntity>, world: Level) : VehicleEntity(type, world) {

    /** Угол вращения РЛС в градусах; крутится, только пока на месте наводчика кто-то сидит. */
    var radarRot = 0f
    var radarRotO = 0f

    /** Боезапас на прошлом тике — по его убыли ловим момент пуска, чтобы дать эффекты. */
    private var lastAmmo = -1

    /** Задержка между заряжаниями, чтобы одно удержание ПКМ не набивало всю укладку. */
    private var loadCooldown = 0

    // длительность start_ext.ogg набора запуска (1.6 c)
    override fun engineStartupDurationTicks() = 32

    override fun getTurretMaxHealth() = 60f

    override fun getWheelMaxHealth() = 50f

    override fun getEngineMaxHealth() = 90f

    override fun baseTick() {
        super.baseTick()
        if (isRemoved) return

        tickRadar()
        if (level().isClientSide) tickExhaust()
        if (loadCooldown > 0) loadCooldown--
        tickLaunchEffects()
    }

    /**
     * Заряжание ЗУР вручную: ПКМ ракетой по торцу любого транспортно-пускового контейнера
     * (боксы `Part: Interactive` в data-json). Заполняется всегда очередной контейнер —
     * боезапас хранится счётчиком, а `BoundUpWithAmmoAmount` сам сажает пуск на нужную направляющую.
     */
    override fun interact(player: Player, hand: InteractionHand): InteractionResult {
        // ВАЖНО: проверка идёт до super. VehicleEntity.interact при maxPassengers > 0 сажает
        // игрока в машину (CONSUME), а по shift открывает контейнер — после него сюда бы
        // просто не дошли. Перехватываем ровно один случай: ракета в руке + взгляд в тубус.
        if (tryLoadMissile(player)) return InteractionResult.SUCCESS

        return super.interact(player, hand)
    }

    private fun tryLoadMissile(player: Player): Boolean {
        val stack = player.mainHandItem
        if (!stack.`is`(ModItems.MEDIUM_ANTI_AIR_MISSILE.get())) return false
        if (loadCooldown > 0 || isWreck) return false

        val looking = OBB.getLookingObb(player, player.entityInteractionRange())
        if (looking == null || looking.part != OBB.Part.INTERACTIVE) return false

        val data = getGunData(MISSILE_WEAPON) ?: return false
        if (data.ammo.get() >= data.get(GunProp.MAGAZINE)) return false

        if (level() is ServerLevel) {
            modifyGunData(MISSILE_WEAPON) { it.ammo.set(it.ammo.get() + 1) }
            if (!player.isCreative) stack.shrink(1)
            // тот же звук, что у заряжания ствола Type-63 — ближайшее по смыслу
            level().playSound(
                null, x, y, z, ModSounds.TYPE_63_RELOAD.get(),
                SoundSource.PLAYERS, 1f, random.nextFloat() * 0.1f + 0.9f
            )
            loadCooldown = 10
        }
        return true
    }

    /** Ловим убыль боезапаса и даём газовую струю из заднего среза тубуса + пыль под машиной. */
    private fun tickLaunchEffects() {
        val level = level()
        val data = getGunData(MISSILE_WEAPON)
        if (level !is ServerLevel || data == null) {
            lastAmmo = -1
            return
        }

        val ammo = data.ammo.get()
        val previous = lastAmmo
        lastAmmo = ammo
        if (previous <= ammo || previous < 0) return

        // BoundUpWithAmmoAmount: пуск идёт с направляющей с индексом (боезапас - 1)
        spawnBackblast(level, (previous - 1).coerceIn(0, RAIL_X.size - 1))
        spawnGroundDust(level)
    }

    private fun spawnBackblast(level: ServerLevel, rail: Int) {
        val transform = getTransformFromString("Turret", 1f)
        val x = RAIL_X[rail]
        val tail = VehicleVecUtils.transformPosition(transform, x, TAIL_Y, TAIL_Z)
        val muzzle = VehicleVecUtils.transformPosition(transform, x, RAIL_Y, RAIL_Z)

        val tailPos = Vec3(tail.x, tail.y, tail.z)
        val muzzlePos = Vec3(muzzle.x, muzzle.y, muzzle.z)
        // струя бьёт вдоль тубуса назад-вниз: от точки схода к заднему срезу
        val direction = muzzlePos.vectorTo(tailPos).normalize()

        ParticleTool.spawnMediumCannonMuzzleParticles(
            direction, tailPos.add(direction.scale(0.5)), level, this
        )
        ParticleTool.sendParticle(
            level, ParticleTypes.CAMPFIRE_COSY_SMOKE,
            tailPos.x, tailPos.y, tailPos.z, 12, 0.3, 0.3, 0.3, 0.08, false
        )
    }

    /** Пороховые газы бьют в землю — поднимаем пыль и выбивают грунт, как у миномёта. */
    private fun spawnGroundDust(level: ServerLevel) {
        val groundState = level.getBlockState(blockPosBelowThatAffectsMyMovement)
        if (!groundState.isAir) {
            ParticleTool.sendParticle(
                level, BlockParticleOption(ParticleTypes.BLOCK, groundState),
                x, y + 0.2, z, 60, 2.2, 0.1, 2.2, 0.5, false
            )
        }
        ParticleTool.sendParticle(
            level, ParticleTypes.CAMPFIRE_COSY_SMOKE,
            x, y + 0.3, z, 18, 2.6, 0.2, 2.6, 0.01, false
        )
    }

    /** Выхлоп идёт, пока двигатель заведён, а не только пока машина едет. */
    private fun tickExhaust() {
        if (isWreck || !engineOn || engineStarting()) return
        val load = Math.abs(power)
        // на холостых дымит реже, под нагрузкой — каждый тик
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

    private fun tickRadar() {
        radarRotO = radarRot
        if (isWreck || getNthEntity(GUNNER_SEAT) == null) return

        radarRot += RADAR_SPEED
        // держим угол в пределах оборота, не ломая интерполяцию между тиками
        if (radarRot >= 360f) {
            radarRot -= 360f
            radarRotO -= 360f
        }
    }

    companion object {
        /** Сиденье наводчика (оно же нулевое — управляет машиной и башней). */
        private const val GUNNER_SEAT = 0

        private const val MISSILE_WEAPON = "Missile"

        /** Град/тик: полный оборот антенны примерно за 3 секунды. */
        private const val RADAR_SPEED = 6f

        /** Срез выхлопной трубы в координатах машины. */
        private val EXHAUST_POS = doubleArrayOf(0.0, 2.313, -4.687)

        /** Направляющие в координатах башни: X контейнеров, точка схода и задний срез. */
        private val RAIL_X = doubleArrayOf(-1.53, -1.093, -0.655, 0.657, 1.095, 1.532)
        private const val RAIL_Y = 2.4025
        private const val RAIL_Z = 0.1959
        private const val TAIL_Y = 0.656
        private const val TAIL_Z = -3.197
    }
}
