package com.atsuishio.superbwarfare.entity.projectile

import com.atsuishio.superbwarfare.entity.vehicle.PantsirEntity
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.init.ModItems
import com.atsuishio.superbwarfare.init.ModSounds
import com.atsuishio.superbwarfare.tools.CustomExplosion
import com.atsuishio.superbwarfare.tools.EntityFindUtil
import com.atsuishio.superbwarfare.tools.ParticleTool
import com.atsuishio.superbwarfare.tools.VectorTool.calculateAngle
import com.atsuishio.superbwarfare.tools.angleTo
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.animal.Pig
import net.minecraft.world.entity.boss.enderdragon.EnderDragon
import net.minecraft.world.item.Item
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import java.util.UUID
import kotlin.math.max

/**
 * 57Э6 — Pantsir-S1's own SAM round. A copy of [Ru9m336MissileEntity] with one
 * fundamental difference: real command-guided missiles (this one included)
 * are NOT fire-and-forget. The real 9К330/57Э6 is guided by a radio uplink —
 * the Pantsir's fire-control radar keeps the target painted while a SEPARATE
 * channel tracks the missile itself (via a tail beacon) and radios steering
 * corrections the whole way to impact. That's the "2 radars on the front of
 * the turret" the target-tracking channel and the missile-tracking channel.
 * Break either one and the missile goes dumb.
 *
 * So unlike every other homing missile in this mod, guidance here requires
 * the SAME Pantsir that fired it to still have that SAME target under a
 * genuine, active lock ([PantsirEntity.trackedTargetUUID]) for the whole
 * flight — not just at the moment of firing. Crucially, this is checked
 * against the Pantsir's real, persistent lock state, NOT by re-measuring
 * the angle to the target from wherever the turret/gunner's aim happens to
 * be pointing this exact tick — a lock that's already been lost doesn't
 * silently resume just because the turret happens to swing back across
 * the target's bearing later.
 *
 * Once that radar lock genuinely drops, though, it's not just dead weight:
 * as long as the SAME gunner is still in the SAME Pantsir, it falls back to
 * manual ATGM-style guidance — steered every tick toward wherever the
 * barrel currently points, exactly like [WireGuideMissileEntity]. Only once
 * nobody's left to fly it (gunner dismounts, vehicle destroyed) does it
 * finally nose over and fall.
 */
open class Ru57e6MissileEntity(type: EntityType<out Ru57e6MissileEntity>, level: Level) :
    MissileProjectile(type, level), BasicGeoProjectileEntity {

    var launcherVehicleUUID: UUID? = null

    fun setLauncherVehicle(uuid: UUID?) {
        this.launcherVehicleUUID = uuid
    }

    var semiAuto: Boolean
        get() = entityData.get(SEMI_AUTO)
        set(value) { entityData.set(SEMI_AUTO, value) }

    private var accelTick = 0

    var semiAutoTargetX: Float
        get() = entityData.get(SEMI_AUTO_TARGET_X)
        set(value) { entityData.set(SEMI_AUTO_TARGET_X, value) }
    var semiAutoTargetY: Float
        get() = entityData.get(SEMI_AUTO_TARGET_Y)
        set(value) { entityData.set(SEMI_AUTO_TARGET_Y, value) }
    var semiAutoTargetZ: Float
        get() = entityData.get(SEMI_AUTO_TARGET_Z)
        set(value) { entityData.set(SEMI_AUTO_TARGET_Z, value) }

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        super.defineSynchedData(builder)
        builder.define(SEMI_AUTO, false)
        builder.define(SEMI_AUTO_TARGET_X, 0f)
        builder.define(SEMI_AUTO_TARGET_Y, 0f)
        builder.define(SEMI_AUTO_TARGET_Z, 0f)
    }

    private var lostSignalTicks = 0

    // Бросок «собьёт / не собьёт» делается ОДИН раз, при первом взятии цели, и
    // дальше только исполняется — иначе ракета переигрывала бы жребий каждый
    // тик и в итоге всегда попадала.
    private var interceptRolled = false
    // Куда целиться, чтобы промахнуться. null — жребий выпал в пользу ракеты,
    // наводимся честно.
    private var missOffset: Vec3? = null
    // Позиция цели на предыдущем тике наведения — из неё измеряется её
    // настоящая скорость, см. tick().
    private var lastTargetPos: Vec3? = null
    private var lastTargetPosTick = 0
    private var fallSpreadApplied = false
    private var launchPos: Vec3? = null
    // "Out of fuel" — once it's covered MAX_FLIGHT_RANGE blocks it goes
    // ballistic and drops, no matter what's currently guiding it (radar
    // lock, manual ATGM steering, doesn't matter). There was previously no
    // limit at all: a missile with an operator still present could fly
    // forever.
    private var fuelExhausted = false

    init {
        this.noCulling = true
    }

    override fun getDefaultItem(): Item {
        return ModItems.MEDIUM_ANTI_AIR_MISSILE.get()
    }

    override fun tick() {
        super.tick()
        accelTick++

        if (launchPos == null) launchPos = position()
        if (!fuelExhausted && launchPos!!.distanceTo(position()) > MAX_FLIGHT_RANGE) {
            // Fuel's gone: force it into the same "abandoned" ballistic-fall
            // state below, overriding whatever guidance was still active —
            // setLost/clearing the target here means the isLost() branch
            // just underneath sees exactly the same state it would for an
            // actually-lost signal.
            fuelExhausted = true
            setLost(true)
            setTargetUUID("none")
        }

        // No more exhaust trail once the fuel's spent — matches the round
        // actually going dead/ballistic instead of still looking powered.
        if (!fuelExhausted) {
            mediumTrail()
        }

        // Semi-auto: the missile flies in the DIRECTION the gunner is
        // looking — not toward a fixed point or entity. Looking at blocks
        // → missile flies toward those blocks. Looking at the sky → missile
        // flies forward through the air. The missile NEVER turns around:
        // it steers to match the view direction, which is always "forward"
        // from the player. Not wire-guided ATGM (barrel vector) — this
        // follows the raw view vector. Updates every tick: move the view
        // → the missile follows.
        if (semiAuto) {
            val owner = this.owner
            val vehicle = owner?.vehicle as? PantsirEntity
            val gunnerPresent = owner != null && vehicle != null && launcherVehicleUUID == vehicle.uuid

            if (fuelExhausted || !gunnerPresent) {
                if (!fallSpreadApplied) {
                    fallSpreadApplied = true
                    val jitterDegrees = (level().random.nextFloat() - 0.5f) * 2f * FALL_SPREAD_DEGREES
                    val horizontal = Vec3(deltaMovement.x, 0.0, deltaMovement.z)
                        .yRot(Math.toRadians(jitterDegrees.toDouble()).toFloat())
                    deltaMovement = Vec3(horizontal.x, deltaMovement.y, horizontal.z)
                }
                val fallVec = Vec3(deltaMovement.x, -deltaMovement.horizontalDistance().coerceAtLeast(1.0), deltaMovement.z)
                turn(fallVec, 3f)
            } else {
                // Beam-riding: the missile rides a beam projected from the
                // barrel. The beam is a ray: start = barrel shoot position,
                // direction = barrel vector. The missile projects itself
                // onto the beam and steers toward a point AHEAD of its
                // current projection — this makes it converge onto the beam
                // line and then fly along it, instead of just matching the
                // barrel direction (which felt like wire-guided ATGM).
                // If the barrel turns, the beam moves, the missile follows.
                val beamStart = vehicle!!.getShootPos(owner, 1f)
                val beamDir = vehicle.getBarrelVector(1f)
                val toMissile = position().subtract(beamStart)
                val projDist = toMissile.dot(beamDir)
                val aimPoint = beamStart.add(beamDir.scale(projDist + 50.0))
                val desiredDir = aimPoint.subtract(position())
                val range = desiredDir.length()
                if (range > 1.0E-4) {
                    turn(desiredDir.normalize(), ((tickCount - 1) * GUIDED_TURN_RAMP).coerceIn(0f, GUIDED_TURN_CAP))
                }
                val accel = (accelTick * ACCEL_RAMP).coerceIn(0f, 1f)
                this.deltaMovement = this.deltaMovement.scale(1.0 - accel * 0.95f).add(lookAngle.scale((CRUISE_SPEED * accel).toDouble()))
            }
            return
        }

        // No target at all (fired without a lock — VehicleFireMessage's
        // uuid was null) is treated exactly like an already-lost signal:
        // there's nothing to command-guide toward, so it falls straight
        // into the same manual ATGM-style fallback below instead of just
        // coasting dead straight with no correction at all.
        if (isLost() || this.getTargetUUID() == "none") {
            val owner = this.owner
            val vehicle = owner?.vehicle as? PantsirEntity

            if (!fuelExhausted && owner != null && vehicle != null && launcherVehicleUUID == vehicle.uuid) {
                // Radar lock's gone, but the gunner who fired it is still
                // right there and can walk it in by eye — same manual
                // fallback WireGuideMissileEntity already uses: steer
                // toward wherever the barrel is CURRENTLY pointed, every
                // tick, continuously — genuine ATGM-style guidance instead
                // of the round just going dumb.
                this.deltaMovement = this.deltaMovement.scale(0.5).add(lookAngle.scale(2.85))
                val lookVec = vehicle.getBarrelVector(1f).scale(1.6)
                val missileVec = vehicle.getShootPos(owner, 1f).vectorTo(position()).normalize()
                turn(missileVec.vectorTo(lookVec), ((tickCount - 1) * MANUAL_TURN_RAMP).coerceIn(0f, MANUAL_TURN_CAP))
                return
            }

            // Nobody left to steer it (gunner dismounted, vehicle
            // destroyed, etc.) — nose over and actually fall (gravity
            // re-enabled below) instead of coasting dead straight forever.
            //
            // Applied once, the instant that happens: a random horizontal
            // kick so every abandoned missile tumbles off on its own
            // heading instead of every single one falling along the exact
            // same extrapolated line and landing in the same spot.
            if (!fallSpreadApplied) {
                fallSpreadApplied = true
                val jitterDegrees = (level().random.nextFloat() - 0.5f) * 2f * FALL_SPREAD_DEGREES
                val horizontal = Vec3(deltaMovement.x, 0.0, deltaMovement.z).yRot(Math.toRadians(jitterDegrees.toDouble()).toFloat())
                deltaMovement = Vec3(horizontal.x, deltaMovement.y, horizontal.z)
            }
            val fallVec = Vec3(deltaMovement.x, -deltaMovement.horizontalDistance().coerceAtLeast(1.0), deltaMovement.z)
            turn(fallVec, 3f)
            return
        }

        val entity = EntityFindUtil.findEntity(this.level(), this.getTargetUUID())
        if (entity == null || this.getTargetUUID() == "none") return

        if ((entity.getPassengers().isNotEmpty() || entity is VehicleEntity)
            && entity.tickCount % (max(0.04 * this.distanceTo(entity), 2.0).toInt()) == 0
        ) {
            entity.level().playSound(
                null,
                entity.onPos,
                if (entity is Pig) SoundEvents.PIG_HURT else ModSounds.MISSILE_WARNING.get(),
                SoundSource.PLAYERS,
                2f,
                1f
            )
        }

        val targetPos = Vec3(
            entity.x,
            entity.y + 0.5f * entity.bbHeight + (if (entity is EnderDragon) -3 else 0),
            entity.z
        )

        // Скорость цели МЕРЯЕМ сами, разностью её позиций между тиками, а не
        // берём из entity.deltaMovement: это договорённость, которую соблюдают
        // не все. Сущность, которую ведут прямым setPos по заранее посчитанной
        // траектории (так устроены ракеты сторонних модов), может оставлять
        // deltaMovement нулевым. Разность позиций правдива для любой.
        val previous = lastTargetPos
        val elapsed = (tickCount - lastTargetPosTick).coerceAtLeast(1)
        val targetVel = if (previous != null && elapsed <= TARGET_VELOCITY_MAX_GAP_TICKS) {
            targetPos.subtract(previous).scale(1.0 / elapsed)
        } else {
            entity.deltaMovement
        }
        lastTargetPos = targetPos
        lastTargetPosTick = tickCount

        if (this.tickCount <= 1) return

        if (!level().isClientSide) rollInterceptOnce(entity)

        // Куда ракета ведёт себя на самом деле: в цель или мимо неё, если
        // жребий оказался не в её пользу.
        val aimPos = missOffset?.let { targetPos.add(it) } ?: targetPos

        // Неконтактный взрыватель — автономен и работает независимо от
        // радиолинии, как и положено собственному взрывателю боевой части.
        // При намеренном промахе он наводится на ту же смещённую точку, так
        // что подрыв происходит рядом с целью, но за пределами её поражения.
        if (!level().isClientSide && tickCount > FUZE_ARM_TICKS &&
            proximityFuze(entity, aimPos, targetVel, virtualPoint = missOffset != null)
        ) return

        if (!hasCommandLink()) {
            lostSignalTicks++
            if (lostSignalTicks > MAX_LOST_SIGNAL_TICKS) {
                setLost(true)
                setTargetUUID("none")
                return
            }
            // Раньше на КАЖДЫЙ тик без подтверждения радиолинии ракета просто
            // летела прямо, и через четыре тика цель стиралась навсегда. На
            // маршевой скорости это и выглядит как «ушла в точку, где был
            // захват»: наведение не работает вовсе, ракета идёт по вектору
            // пуска. Между тем разрыв на несколько тиков — обычное дело
            // (heartbeat от клиента идёт раз в два тика, плюс сетевая
            // неравномерность), и настоящая ракета в такой момент не глупеет:
            // она доводится по последним принятым данным. Так что внутри окна
            // ожидания продолжаем наводиться, а не летим по прямой; окно ниже
            // расширено с четырёх тиков до двух секунд.
        } else {
            lostSignalTicks = 0
        }

        // ── Пропорциональное сближение ────────────────────────────────────
        // Ракета доворачивает не «на пересчитанную точку встречи», а
        // пропорционально скорости вращения линии визирования:
        //
        //     Ω = (r × v_отн) / |r|²        — угловая скорость линии визирования
        //     a = N · (Ω × V)               — потребная поперечная перегрузка
        //
        // Весь смысл в том, что на курсе встречи линия визирования не вращается
        // вовсе: Ω = 0, команды нет, ракета идёт по прямой. Любая ошибка
        // заставляет линию визирования поворачиваться, и ракета выбирает её
        // ЗАРАНЕЕ, пока дистанция велика и та же угловая скорость стоит копеек.
        //
        // Прежний закон — каждый тик заново решать задачу встречи и
        // разворачиваться прямо на решение — это чистое преследование. Ошибка
        // выгребалась не заранее, а на подлёте, где потребная угловая скорость
        // растёт лавинообразно: на маршевых 11.4 блока/тик потолка в 15.8°/тик уже не
        // хватало, и ракета проходила мимо в нескольких блоках.
        // Экстраполировать цель вперёд на тик (в расчёте на то, что она тикает
        // после ракеты и её координаты прочитаны устаревшими) намеренно НЕ
        // делаем. Численная проверка: если запаздывание есть, оно стоит 1.3
        // блока промаха — это внутри радиуса неконтактного взрывателя, то есть
        // ничего не стоит. Если же запаздывания нет, а компенсация стоит,
        // ракета уходит на 6–7 блоков ВПЕРЁД цели, и это уже мимо взрывателя.
        // Ставка проигрышная в среднем, поэтому линию визирования строим по
        // тому, что видим.
        val los = aimPos.subtract(position())
        val range = los.length()
        val missileVel = deltaMovement
        val speed = missileVel.length()
        val relVel = targetVel.subtract(missileVel)
        // Положительна, пока дистанция сокращается
        val closingSpeed = -relVel.dot(los.scale(1.0 / range.coerceAtLeast(1.0E-4)))

        val toVec = if (range < 1.0E-4 || speed < 1.0E-4 || closingSpeed <= 0.0) {
            // Вырожденная геометрия или догон вдогонку, когда сближения нет
            // вовсе: пропорциональное сближение тут не определено, правим
            // просто в цель.
            los
        } else {
            val omega = los.cross(relVel).scale(1.0 / (range * range))
            missileVel.add(omega.cross(missileVel).scale(NAV_CONSTANT))
        }

        // Прежнее правило «цель безнадёжно позади — бросаем», но угол теперь
        // меряется до самой цели, а не до вектора команды: у ПН команда по
        // построению всегда лежит рядом с текущей скоростью, и проверка в
        // прежнем виде не сработала бы никогда.
        setLostTarget(calculateAngle(missileVel, targetPos.subtract(position())) > 120 && !isLostTarget())

        if (!isLostTarget()) {
            turn(toVec, ((tickCount - 1) * GUIDED_TURN_RAMP).coerceIn(0f, GUIDED_TURN_CAP))
            val accel = (accelTick * ACCEL_RAMP).coerceIn(0f, 1f)
            this.deltaMovement = this.deltaMovement.scale(1.0 - accel * 0.95f).add(lookAngle.scale((CRUISE_SPEED * accel).toDouble()))
        }

        if (isLostTarget()) {
            this.setTargetUUID("none")
        }
    }

    /**
     * Жребий «собьёт / не собьёт», один раз за полёт. По малоразмерной цели
     * поражение — вопрос вероятности, а не арифметики: осколочное поле у неё
     * либо накрывает планер, либо проходит мимо. Поэтому исход решается сразу,
     * при взятии цели, а дальше ракета его только отыгрывает — и отыгрывает
     * честно, полётом, а не исчезновением урона в момент подрыва.
     *
     * Проигранный жребий превращается в промах: точка прицеливания уводится в
     * случайную сторону — вперёд, назад, вбок, вверх — на расстояние заведомо
     * большее радиуса поражения, так что подрыв происходит рядом с целью, но
     * ей ничего не делает.
     */
    private fun rollInterceptOnce(target: Entity) {
        if (interceptRolled) return
        interceptRolled = true

        val chance = INTERCEPT_CHANCE.entries
            .firstOrNull { (id, _) -> target.type.builtInRegistryHolder().key().location().toString() == id }
            ?.value
            ?: return

        if (level().random.nextDouble() <= chance) return

        val random = level().random
        val theta = random.nextDouble() * 2.0 * Math.PI
        val cosPhi = random.nextDouble() * 2.0 - 1.0
        val sinPhi = kotlin.math.sqrt(1.0 - cosPhi * cosPhi)
        val distance = MISS_DISTANCE_MIN + random.nextDouble() * (MISS_DISTANCE_MAX - MISS_DISTANCE_MIN)
        missOffset = Vec3(sinPhi * kotlin.math.cos(theta), cosPhi, sinPhi * kotlin.math.sin(theta)).scale(distance)
    }

    /**
     * Неконтактный (радио)взрыватель. Настоящая 57Э6 несёт осколочно-стержневую
     * боевую часть и на прямое попадание не рассчитана вовсе — подрыв идёт по
     * команде собственного взрывателя в точке наибольшего сближения.
     *
     * Точка эта ищется аналитически ВНУТРИ тика, а не сравнением расстояний на
     * его границах: при 11.4 блока/тик у ракеты и сопоставимой скорости у цели
     * они успевают разойтись между двумя замерами, и наименьшее сближение
     * приходится на середину шага. Решается минимум |r + v_отн·t| по t на
     * отрезке [0, 1]; получившийся t даёт и промах, и место подрыва.
     *
     * @return true, если ракета подорвана — вызывающему коду дальше делать
     * нечего.
     */
    private fun proximityFuze(target: Entity, targetPos: Vec3, targetVel: Vec3, virtualPoint: Boolean): Boolean {
        val r = targetPos.subtract(position())
        val relVel = targetVel.subtract(deltaMovement)
        val relSpeedSq = relVel.lengthSqr()

        // Момент наибольшего сближения в долях тика
        val t = if (relSpeedSq < 1.0E-9) 0.0 else (-r.dot(relVel) / relSpeedSq).coerceIn(0.0, 1.0)
        // Промах меряем до габарита цели, а не до её центра: у крупной техники
        // разница в несколько блоков.
        // У виртуальной точки промаха габаритов нет — иначе крупная цель
        // «дотягивалась» бы до неё своей же полушириной и подрыв случался бы
        // ближе, чем задумано.
        val miss = r.add(relVel.scale(t)).length() - if (virtualPoint) 0.0 else target.bbWidth * 0.5

        if (miss > FUZE_RADIUS) return false

        // Бросок промаха — один раз, ДО взрыва, через Math.random
        // (не level().random — у него были проблемы с детерминированным сидом).
        hitAircraft = target is MissileProjectile ||
            target.javaClass.simpleName.contains("Missile")
        if (hitAircraft && !level().isClientSide && !damageRollDone) {
            damageRollDone = true
            if (Math.random() < 0.05) {
                damageMultiplier = 0f
            }
        }

        causeExplode(position().add(deltaMovement.scale(t)))

        // Взрыв уже разошёлся и урон посчитан — проверяем, пережила ли его
        // цель. Если нет, сыплем обломки от ЕЁ позиции, а не от точки подрыва:
        // падает именно то, что от цели осталось. Заодно это внятное
        // подтверждение попадания на дистанции, где саму цель не разглядеть.
        val wreck = !target.isAlive || target.isRemoved
        if (wreck) {
            ParticleTool.spawnWreckageFallParticles(level(), target.boundingBox.center)
        }

        discard()
        return true
    }

    // Guidance is tied to the Pantsir's genuine, PERSISTENT lock state
    // (PantsirEntity.trackedTargetUUID — the same flag driving the turret's
    // own auto-tracking and the on-screen lock box), not to some angle
    // freshly re-measured against wherever the barrel happens to be
    // pointing this exact tick.
    //
    // That distinction matters: an earlier version of this recomputed the
    // angle from the CURRENT boresight every tick, which made the missile
    // behave like a wire-guided ATGM — it kept re-acquiring guidance the
    // instant the barrel/gunner's aim swept back near the target, even
    // AFTER the actual lock had already dropped (gunner looked away, lock
    // expired, then looked back without re-locking). A real command-guided
    // round doesn't work that way: once the fire-control system's lock is
    // gone, that's it — the round goes ballistic and stays that way, it
    // doesn't silently resume just because the turret happens to swing
    // back through the target's bearing.
    private fun hasCommandLink(): Boolean {
        if (semiAuto) return true
        val owner = this.owner ?: return false
        val vehicle = owner.vehicle as? PantsirEntity ?: return false
        if (launcherVehicleUUID != vehicle.uuid) return false
        if (vehicle.getSeatIndex(owner) != 2 || !vehicle.jacksDeployed) return false
        return vehicle.trackedTargetUUID == this.getTargetUUID()
    }

    // No gravity while actively radar-guided (matches every other missile
    // in the mod) — and NONE either while a gunner is still manually
    // walking it in ATGM-style, whether that's after losing lock OR because
    // it was fired with no target to begin with (same as
    // WireGuideMissileEntity, which never has gravity at all). Only falls
    // once genuinely abandoned — see the matching branch in tick().
    override fun getCustomGravity(): Float {
        if (fuelExhausted) return 0.05f
        if (!isLost() && this.getTargetUUID() != "none") return 0f
        val owner = this.owner
        val vehicle = owner?.vehicle as? PantsirEntity
        val stillManuallyControlled = owner != null && vehicle != null && launcherVehicleUUID == vehicle.uuid
        return if (stillManuallyControlled) 0f else 0.05f
    }

    // Воздушный подрыв вместо наземного пресета, который подбирается по
    // радиусу заряда. Тот сыпал пыль и обломки по земле — на высоте, где
    // срабатывает зенитная ракета, это выглядело чужеродно.
    private var damageRollDone = false
    private var damageMultiplier = 1.0f
    private var hitAircraft = false

    override fun buildExplosion(vec3: Vec3): CustomExplosion.Builder {
        val builder = super.buildExplosion(vec3).withParticleType(ParticleTool.ParticleType.AIRBURST)
        if (damageMultiplier < 1.0f) {
            builder.damage(getExplosionDamage() * damageMultiplier)
        }
        return builder
    }

    override fun afterHitEntity(result: net.minecraft.world.phys.EntityHitResult) {
        super.afterHitEntity(result)
    }

    override fun getSound(): SoundEvent {
        return ModSounds.ROCKET_FLY.get()
    }

    override fun getVolume(): Float {
        return 0.4f
    }

    companion object {
        // PantsirEntity.trackedTargetUUID already carries its own ~10-tick
        // grace (baseTick's heartbeat timeout) before it clears — this is
        // just a little extra slack to ride out sync-packet timing between
        // server and this entity, not a second "forgiveness window" on top.
        // Манёвренность (град/тик): скорость доворота нарастает первые тики
        // после схода с направляющей и упирается в потолок. Обе величины —
        // и темп нарастания, и потолок — задаются вместе: множить только потолок
        // смысла нет — на ближней дистанции ракета до него просто не успевает
        // разогнаться, и разница достаётся только дальним пускам. Верхняя пара
        // значений — радиокомандное наведение, нижняя — ручное довождение по
        // стволу после потери захвата.
        /** Маршевая скорость, блоков/тик. */
        private const val CRUISE_SPEED = 6.05f

        // Прирост доли маршевой скорости за тик после схода. 0.05 = полная
        // скорость за ~20 тиков (1 секунду). До этого ракета летит на
        // стартовой скорости (Velocity из JSON), постепенно набирая ход.
        private const val ACCEL_RAMP = 0.04f

        // Навигационная постоянная пропорционального сближения. Классический
        // рабочий диапазон 3–5: меньше — ракета лениво выбирает ошибку и
        // доедает её у цели, больше — дёргается на шумах измерения.
        private const val NAV_CONSTANT = 4.0

        private const val GUIDED_TURN_RAMP = 0.528f
        private const val GUIDED_TURN_CAP = 15.84f
        private const val MANUAL_TURN_RAMP = 0.4224f
        private const val MANUAL_TURN_CAP = 6.336f

        // Две секунды вместо прежних четырёх тиков: heartbeat захвата идёт с
        // клиента раз в два тика, и разрыв в несколько тиков — норма, а не
        // повод превращать ракету в болванку на весь остаток полёта.
        private const val MAX_LOST_SIGNAL_TICKS = 40

        /**
         * Вероятность поражения по типам целей. Всё, чего здесь нет,
         * поражается без броска — обычной техникой ракета занимается штатно.
         */
        private val INTERCEPT_CHANCE = mapOf(
            "wrbdrones:shahed136" to 0.7,
        )

        // Насколько ракета уводится от цели при проигранном жребии. Нижняя
        // граница взята с запасом от радиуса поражения (8.4), чтобы взрыв
        // гарантированно не задел цель, верхняя — чтобы промах читался как
        // промах, а не как пуск в белый свет.
        private const val MISS_DISTANCE_MIN = 11.0
        private const val MISS_DISTANCE_MAX = 16.0

        // Радиус срабатывания неконтактного взрывателя. Меньше радиуса самой
        // боевой части (8.4 в pantsir_s1.json) — подрыв на границе поражения
        // смысла не имеет, цель должна попасть в осколочное поле уверенно.
        private const val FUZE_RADIUS = 3.0

        // Взводится не сразу после схода: иначе ракета, выпущенная по цели в
        // упор, подорвалась бы прямо на направляющей.
        private const val FUZE_ARM_TICKS = 10

        // Если наведение не выполнялось дольше этого, разность позиций уже не
        // характеризует скорость (цель могла сманеврировать) — берём то, что
        // сущность сообщает о себе сама.
        private const val TARGET_VELOCITY_MAX_GAP_TICKS = 10
        private const val FALL_SPREAD_DEGREES = 25f
        // Запас топлива должен быть заметно больше дальности захвата (1680 в
        // pantsir_s1.json): ракета идёт не по прямой — упреждение, довороты,
        // набор высоты, — поэтому пройденный путь всегда длиннее дистанции до
        // цели. Держим прежний полуторакратный запас: цель, захваченная на
        // предельной дальности, иначе гарантированно недосягаема — топливо
        // кончится раньше.
        private const val MAX_FLIGHT_RANGE = 2640.0

        @JvmField
        val SEMI_AUTO: EntityDataAccessor<Boolean> =
            SynchedEntityData.defineId(Ru57e6MissileEntity::class.java, EntityDataSerializers.BOOLEAN)
        @JvmField
        val SEMI_AUTO_TARGET_X: EntityDataAccessor<Float> =
            SynchedEntityData.defineId(Ru57e6MissileEntity::class.java, EntityDataSerializers.FLOAT)
        @JvmField
        val SEMI_AUTO_TARGET_Y: EntityDataAccessor<Float> =
            SynchedEntityData.defineId(Ru57e6MissileEntity::class.java, EntityDataSerializers.FLOAT)
        @JvmField
        val SEMI_AUTO_TARGET_Z: EntityDataAccessor<Float> =
            SynchedEntityData.defineId(Ru57e6MissileEntity::class.java, EntityDataSerializers.FLOAT)
    }
}
