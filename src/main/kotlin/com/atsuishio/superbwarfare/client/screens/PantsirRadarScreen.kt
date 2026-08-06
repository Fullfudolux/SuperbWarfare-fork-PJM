package com.atsuishio.superbwarfare.client.screens

import com.atsuishio.superbwarfare.client.ClientSyncedEntityHandler
import com.atsuishio.superbwarfare.data.vehicle.subdata.VehicleType
import com.atsuishio.superbwarfare.entity.vehicle.PantsirEntity
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.event.ClientEventHandler
import com.atsuishio.superbwarfare.init.ModKeyMappings
import com.atsuishio.superbwarfare.init.ModTags
import com.atsuishio.superbwarfare.tools.SeekTool
import com.mojang.math.Axis
import net.minecraft.core.BlockPos
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.tags.BlockTags
import net.minecraft.util.Mth
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.phys.Vec3
import net.neoforged.api.distmarker.Dist
import net.neoforged.api.distmarker.OnlyIn
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * AA search-radar readout for the Pantsir gunner seat. Only reachable via
 * [com.atsuishio.superbwarfare.event.ClickEventHandler] while seated at the
 * gunner position (seat index 2) with the radar fully deployed.
 *
 * The sweep line tracks [PantsirEntity.radarWorldBearing] — the SAME angle
 * the physical dish is actually facing in the world — so it stays in sync
 * with what the player sees out the window. Target blips only "light up"
 * when the sweep passes their bearing, then fade out over one full sweep
 * revolution, matching how a real search radar reveals contacts.
 */
@OnlyIn(Dist.CLIENT)
class PantsirRadarScreen(private val vehicle: PantsirEntity) :
    Screen(Component.translatable("container.superbwarfare.pantsir_radar")) {

    // Форма отметки. По подтипам не дробим: все ракеты, снаряды и бомбы —
    // одна иконка.
    private enum class ContactKind { AIRPLANE, HELICOPTER, MISSILE }

    // Насколько клетка на пути луча мешает наблюдению.
    private enum class RadarCover { CLEAR, SOFT, SOLID }

    private class Blip(
        // Derived from the entity's UUID, not its network id — network ids
        // are handed out sequentially per session, so two of the same
        // helicopter spawned back-to-back would otherwise show consecutive
        // numbers (e.g. "1"/"2"), reading like a duplicate counter instead
        // of a real contact identifier.
        val displayId: Int,
        val typeName: String,
        val kind: ContactKind,
        var bearing: Float,
        var range: Double,
        var height: Double,
        var friendly: Boolean,
        var lastHitTick: Int,
        var tracked: Boolean,
        // Нужен, чтобы отметку можно было взять на сопровождение прямо с
        // экрана — клик по ней передаёт эту цель в ClientEventHandler.
        var entity: net.minecraft.world.entity.Entity,
    )

    private val blips = LinkedHashMap<Int, Blip>()
    private var ticks = 0

    // Верх шкалы высот подстраивается под самый высокий контакт (с шагом в
    // AH_HEIGHT_STEP), а не сидит на фиксированных 100 блоках: крылатые и
    // баллистические ракеты идут на высотах в сотни блоков и на прежней шкале
    // все до единой упирались в верхнюю кромку индикатора, теряя всю разницу
    // по высоте между собой.
    private var ahMaxHeight = AH_MIN_SCALE

    override fun isPauseScreen() = false

    // While this screen is open, normal in-game key handling
    // (ClickEventHandler.onKeyPressed) never fires — it bails out early via
    // notInGame the instant mc.screen != null. So the Z key that opened the
    // radar wouldn't otherwise do anything while it's up; catch it here
    // directly so a second press toggles the screen closed again.
    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (keyCode == ModKeyMappings.VEHICLE_INTERACT.key.value) {
            this.minecraft?.setScreen(null)
            return true
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    // The vanilla menu-background blur post-effect applies to the WHOLE
    // frame, including our own grid/text drawn on top of it — same fix as
    // WeaponEditScreen uses for the same reason (a HUD-like overlay opened
    // mid-gameplay, not a paused inventory menu). A flat dark fill instead
    // still dims the world behind without blurring our own readouts.
    override fun renderBackground(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        guiGraphics.fill(0, 0, this.width, this.height, 0x70000000.toInt())
    }

    override fun tick() {
        super.tick()

        val player = this.minecraft?.player
        if (player == null || player.vehicle !== vehicle || vehicle.getSeatIndex(player) != 2 || vehicle.jacksProgress < 1f) {
            this.minecraft?.setScreen(null)
            return
        }

        ticks++
        updateBlips(player)
    }

    private fun updateBlips(player: net.minecraft.world.entity.player.Player) {
        val level = vehicle.level()
        val origin = vehicle.position()
        val radarPos = vehicle.radarWorldPos()
        val seen = HashSet<Int>()

        val nearbyVehicles = level.getEntitiesOfClass(VehicleEntity::class.java, vehicle.boundingBox.inflate(LOCAL_QUERY_RANGE))
        // Загоризонтные контакты (сервер шлёт их через EntitySyncMessage из
        // VehicleEntity.vehicleRadar) — БЕЗ фильтра по VehicleEntity: ракеты
        // приходят тем же каналом, и раньше именно этот filterIsInstance
        // выбрасывал их, из-за чего за пределами прогруза чанков радар видел
        // только технику. Отдельно запоминаем их id: у таких целей луч видимости
        // уже проверен на сервере от самой антенны, а клиентский raycast по
        // невыгруженным чанкам всё равно ничего осмысленного не даст.
        // Призраки, чей настоящий entity уже прогружен, отбрасываются: их
        // координаты — снимок на момент последнего пакета, и захват, севший на
        // такую копию, тянул бы рамку по устаревшей позиции.
        val syncedContacts = ClientSyncedEntityHandler.getSyncedEntities(level)
            .filter { level.getEntity(it.id) == null }
        val syncedIds = syncedContacts.mapTo(HashSet()) { it.id }
        // In-flight missiles/rockets show up as their own contacts too, not
        // just aircraft/helicopters — a real search radar tracks incoming
        // ordnance as threats in their own right. Not just AA_MISSILE (this
        // Pantsir's own 57Э6 round's tag) — AT_ROCKET (RPGs) and
        // DESTROYABLE_PROJECTILE (AGM/Javelin/wire-guided ATGMs, guided
        // bombs, the swarm drone) too, so ATGMs and other guided munitions
        // show up, not only the AA-specific ones.
        val nearbyMissiles = level.getEntitiesOfClass(
            net.minecraft.world.entity.Entity::class.java,
            vehicle.boundingBox.inflate(LOCAL_QUERY_RANGE)
        ) { isMissileLike(it) }

        for (candidate in nearbyVehicles.asSequence() + nearbyMissiles.asSequence() + syncedContacts.asSequence()) {
            if (candidate === vehicle) continue
            val isMissile = isMissileLike(candidate)
            val isAircraft = candidate is VehicleEntity &&
                    (candidate.vehicleType == VehicleType.AIRPLANE || candidate.vehicleType == VehicleType.HELICOPTER)
            if (!isMissile && !isAircraft) continue
            if (seen.contains(candidate.id)) continue

            // Low-flying contacts (hugging the ground/treetops) don't
            // register — only altitude more than 10 blocks above the
            // Pantsir's own emplacement shows up. NOT the same "height
            // above whatever's directly underneath it" measure the missile
            // lock-on's MinTargetHeight uses — that one resets to ~0 the
            // instant a target lands/parks on ANY surface (a rooftop, a
            // helipad, a tall platform), which is fine for "is this thing
            // currently flying" but wrong for a search radar's altitude
            // gate: a helicopter parked on a platform 20 blocks up is
            // genuinely up there regardless of what it's resting on.
            // Missiles skip this gate entirely — a sea-skimming/low
            // incoming round is still a threat worth showing.
            if (!isMissile && candidate.y - origin.y < MIN_TARGET_HEIGHT) continue

            val dx = candidate.x - origin.x
            val dz = candidate.z - origin.z
            val range = sqrt(dx * dx + dz * dz)
            if (range > RANGE) continue

            // Solid terrain/obstacles block the radar return — fences,
            // leaves and other non-full blocks don't. Загоризонтные контакты
            // пропускаем: их видимость сервер уже проверил.
            if (candidate.id !in syncedIds && !hasLineOfSight(level, radarPos, candidate.boundingBox.center)) continue

            seen.add(candidate.id)
            val bearing = Mth.wrapDegrees(Math.toDegrees(kotlin.math.atan2(-dx, dz)).toFloat())
            val height = candidate.y - origin.y
            // Missiles aren't VehicleEntity/passenger-carrying, so
            // SeekTool.IS_FRIENDLY's team/passenger/lastDriver checks don't
            // apply to them — go straight to the firing entity's own team
            // via vanilla Projectile.getOwner() instead.
            val friendly = if (isMissile) {
                val owner = (candidate as? net.minecraft.world.entity.projectile.Projectile)?.owner
                owner != null && SeekTool.IN_SAME_TEAM.test(player, owner)
            } else {
                SeekTool.IS_FRIENDLY.test(player, candidate)
            }

            val kind = when {
                isMissile -> ContactKind.MISSILE
                (candidate as? VehicleEntity)?.vehicleType == VehicleType.HELICOPTER -> ContactKind.HELICOPTER
                else -> ContactKind.AIRPLANE
            }

            val existing = blips[candidate.id]
            if (existing != null) {
                existing.bearing = bearing
                existing.range = range
                existing.height = height
                existing.friendly = friendly
                existing.tracked = true
                existing.entity = candidate
            } else {
                val displayId = (candidate.uuid.hashCode() and 0x7FFFFFFF) % 100
                val typeName = if (isMissile) "РАКЕТА" else (candidate.displayName?.string ?: candidate.type.description.string)
                blips[candidate.id] = Blip(displayId, typeName, kind, bearing, range, height, friendly, -1, true, candidate)
            }
        }

        // Contacts not seen this tick (out of range, unloaded, destroyed)
        // stop updating position — the dot freezes where it last was and
        // just keeps fading, like a real radar losing a return, instead of
        // vanishing instantly.
        for ((id, blip) in blips) {
            if (id !in seen) blip.tracked = false
        }

        val sweep = vehicle.radarWorldBearing()
        for (blip in blips.values) {
            val diff = abs(Mth.wrapDegrees(blip.bearing - sweep))
            if (diff <= SWEEP_HALF_WIDTH) blip.lastHitTick = ticks
        }

        blips.values.removeIf { !it.tracked && ticks - it.lastHitTick > FADE_TICKS }

        val highest = blips.values.maxOfOrNull { it.height } ?: 0.0
        ahMaxHeight = (ceil(highest / AH_HEIGHT_STEP) * AH_HEIGHT_STEP).coerceAtLeast(AH_MIN_SCALE)
    }

    // Один тег вместо перечисления трёх: RADAR_CONTACT включает в себя
    // AA_MISSILE/AT_ROCKET/DESTROYABLE_PROJECTILE и дополнительно — боеприпасы
    // сторонних модов (pjmbasemod:strategic_missile), которые сами по себе ни
    // в один из этих трёх тегов не входят.
    private fun isMissileLike(entity: net.minecraft.world.entity.Entity): Boolean =
        entity.type.`is`(ModTags.EntityTypes.RADAR_CONTACT)

    // Steps along the segment in ~1-block increments, checking each
    // distinct block for obstruction. A real full-cube obstacle (terrain,
    // walls, a hull) blocks the return; fences, leaves and other non-full
    // blocks are transparent one at a time, but they accumulate — see
    // SOFT_COVER_LIMIT.
    private fun hasLineOfSight(level: Level, from: Vec3, to: Vec3): Boolean {
        val diff = to.subtract(from)
        val dist = diff.length()
        if (dist < 1.0) return true

        val steps = ceil(dist).toInt()
        val step = diff.scale(1.0 / steps)
        var pos = from
        var lastPos: BlockPos? = null
        var softCover = 0
        for (i in 1 until steps) {
            pos = pos.add(step)
            val blockPos = BlockPos.containing(pos)
            if (blockPos == lastPos) continue
            lastPos = blockPos

            when (coverAt(level, blockPos)) {
                RadarCover.SOLID -> return false
                RadarCover.SOFT -> {
                    // Каждая такая клетка сама по себе прозрачна, но не
                    // бесплатна: набралось SOFT_COVER_LIMIT — луч считается
                    // погашенным. Иначе достаточно было выстроить цель за
                    // лесополосой любой глубины, и радар всё равно видел её
                    // насквозь, будто там чистое поле.
                    softCover++
                    if (softCover >= SOFT_COVER_LIMIT) return false
                }

                RadarCover.CLEAR -> {}
            }
        }
        return true
    }

    /**
     * Насколько клетка мешает лучу: SOLID гасит сразу, SOFT копится в счётчике
     * (см. [SOFT_COVER_LIMIT]), CLEAR не мешает вовсе.
     */
    private fun coverAt(level: Level, pos: BlockPos): RadarCover {
        val state = level.getBlockState(pos)
        if (state.isAir) return RadarCover.CLEAR

        // Листва, заборы, стенки: по отдельности сквозь них видно.
        if (state.`is`(BlockTags.LEAVES) ||
            state.`is`(BlockTags.FENCES) ||
            state.`is`(BlockTags.FENCE_GATES) ||
            state.`is`(BlockTags.WALLS)
        ) {
            return RadarCover.SOFT
        }

        val shape = state.getCollisionShape(level, pos)
        // Трава, цветы, факелы и прочее без коллизии для луча не существуют —
        // в счётчик они не идут, иначе густой луг «закрывал» бы небо.
        if (shape.isEmpty) return RadarCover.CLEAR
        return if (Block.isShapeFullBlock(shape)) RadarCover.SOLID else RadarCover.SOFT
    }

    private fun icoRadius(): Int = (((this.height - 80) / 2).coerceIn(150, 420) / 1.1f).toInt()

    // Экранная позиция отметки на круговом индикаторе — одна и та же формула
    // для отрисовки и для попадания курсором, чтобы кликалось ровно туда, куда
    // нарисовано.
    private fun blipScreenPos(blip: Blip, cx: Int, cy: Int, half: Int): Pair<Int, Int> {
        val rad = Math.toRadians(relativeBearing(blip.bearing).toDouble())
        val fraction = (blip.range / RANGE).coerceIn(0.0, 1.0)
        return Pair(
            cx + (sin(rad) * fraction * half).toInt(),
            cy - (cos(rad) * fraction * half).toInt()
        )
    }

    /**
     * Захват прямо с экрана радара: клик по вражеской отметке передаёт цель в
     * систему наведения. Обычный захват требует навести ствол на цель в пределах
     * SeekAngle — на дальностях в сотни блоков цель не видно даже как точку,
     * поэтому единственный практичный способ взять её на сопровождение — выбрать
     * контакт на индикаторе, а дальше башня доворачивается сама.
     */
    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button)

        val player = this.minecraft?.player ?: return false
        val cx = this.width / 2
        val cy = this.height / 2
        val half = icoRadius()

        var best: Blip? = null
        var bestDistSq = CLICK_RADIUS * CLICK_RADIUS
        for (blip in blips.values) {
            if (blip.friendly || blip.lastHitTick < 0) continue
            val (px, py) = blipScreenPos(blip, cx, cy, half)
            val dx = mouseX - px
            val dy = mouseY - py
            val distSq = dx * dx + dy * dy
            if (distSq <= bestDistSq) {
                bestDistSq = distSq
                best = blip
            }
        }

        val target = best?.entity ?: return super.mouseClicked(mouseX, mouseY, button)
        ClientEventHandler.lockTargetFromRadar(player, target)
        return true
    }

    override fun render(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick)

        // The circular indicator (ИКО) is dead-centered on screen, sized off
        // the screen's own height so it grows/shrinks with the window
        // instead of staying pinned to a fixed pixel radius. The
        // Азимут-Высота indicator fills whatever column is left over to its
        // left.
        val centerX = this.width / 2
        val centerY = this.height / 2
        val half = icoRadius()

        // Background disc's edge now matches the outer ring exactly (was
        // half+10, spilling a visible halo past the actual grid).
        fillCircle(guiGraphics, centerX, centerY, half, 0xE0041A0A.toInt())

        drawGrid(guiGraphics, centerX, centerY, half)
        drawSweep(guiGraphics, centerX, centerY, half)
        drawBlips(guiGraphics, centerX, centerY, half)

        // Both side panels share the ИКО circle's own bottom edge instead of
        // an independent fraction-of-screen-height guess, so all three end
        // flush with each other.
        val radarBottom = centerY + half

        val ahLeft = 20
        val ahRight = (centerX - half - 12).coerceAtLeast(ahLeft + 110)
        val ahTop = (this.height * 0.64f).toInt()
        drawAzimuthHeightIndicator(guiGraphics, ahLeft, ahTop, ahRight, radarBottom)

        val listLeft = (centerX + half + 22)
        val listRight = (this.width - 20).coerceAtLeast(listLeft + 180)
        val listTop = (this.height * 0.08f).toInt()
        drawTargetList(guiGraphics, listLeft, listTop, listRight, radarBottom)

        super.render(guiGraphics, mouseX, mouseY, partialTick)
    }

    private fun drawGrid(guiGraphics: GuiGraphics, cx: Int, cy: Int, half: Int) {
        val gridColor = 0xFF1E6B2E.toInt()

        // Round range rings.
        var r = half
        while (r > 0) {
            drawCircleOutline(guiGraphics, cx, cy, r, gridColor)
            r -= half / RING_COUNT
        }

        // Crosshair.
        guiGraphics.fill(cx - half, cy, cx + half, cy + 1, gridColor)
        guiGraphics.fill(cx, cy - half, cx + 1, cy + half, gridColor)

        // Just inside the outer ring on all 4 sides, not spilling outside
        // the circle.
        drawDegreeLabelCentered(guiGraphics, "0", cx, cy - half + 3, 0x39FF6A)
        drawDegreeLabelCentered(guiGraphics, "180", cx, cy + half - 11, 0x39FF6A)
        val label270 = "270°"
        val label90 = "90°"
        guiGraphics.drawString(this.font, label270, cx - half + 4, cy - 4, 0x39FF6A)
        guiGraphics.drawString(this.font, label90, cx + half - 4 - this.font.width(label90), cy - 4, 0x39FF6A)
    }

    // drawCenteredString centers the WHOLE string, "°" included — since the
    // degree sign isn't the same width as a digit, that pulls the actual
    // NUMBER off-center from cx. Center on the digits only and tack the "°"
    // on afterwards, unable to affect the centering math.
    private fun drawDegreeLabelCentered(guiGraphics: GuiGraphics, degrees: String, cx: Int, y: Int, color: Int) {
        val numWidth = this.font.width(degrees)
        val startX = cx - numWidth / 2
        guiGraphics.drawString(this.font, degrees, startX, y, color)
        guiGraphics.drawString(this.font, "°", startX + numWidth, y, color)
    }

    // Filled disc via horizontal scanlines — one fill() per row instead of
    // per-pixel, cheap enough to redraw every frame.
    private fun fillCircle(guiGraphics: GuiGraphics, cx: Int, cy: Int, radius: Int, color: Int) {
        for (dy in -radius..radius) {
            val dx = kotlin.math.sqrt((radius * radius - dy * dy).toDouble()).toInt()
            guiGraphics.fill(cx - dx, cy + dy, cx + dx + 1, cy + dy + 1, color)
        }
    }

    // No native circle-outline draw in GuiGraphics — plot points around the
    // circumference and fill a tiny dot at each, dense enough (6 samples
    // per pixel of radius) that adjacent dots overlap into a solid ring.
    private fun drawCircleOutline(guiGraphics: GuiGraphics, cx: Int, cy: Int, radius: Int, color: Int) {
        val steps = (radius * 6).coerceAtLeast(90)
        for (i in 0 until steps) {
            val angle = 2.0 * Math.PI * i / steps
            val x = cx + (radius * cos(angle)).toInt()
            val y = cy + (radius * sin(angle)).toInt()
            guiGraphics.fill(x, y, x + 1, y + 1, color)
        }
    }

    // Bearing relative to where the TURRET is currently aimed, not world
    // north — so if the gunner is looking straight at a contact, it (and
    // the sweep line, when it's pointed the same way) shows up at the top
    // of the screen, "in front", matching what's out the window.
    private fun relativeBearing(worldBearing: Float): Float =
        Mth.wrapDegrees(worldBearing - vehicle.turretFacingBearing())

    private fun drawSweep(guiGraphics: GuiGraphics, cx: Int, cy: Int, half: Int) {
        val bearing = relativeBearing(vehicle.radarWorldBearing())

        val poseStack = guiGraphics.pose()
        poseStack.pushPose()
        poseStack.rotateAround(Axis.ZP.rotationDegrees(bearing), cx.toFloat(), cy.toFloat(), 0f)
        guiGraphics.fill(cx - 1, cy - half, cx + 1, cy, 0xFF39FF6A.toInt())
        poseStack.popPose()
    }

    private fun drawBlips(guiGraphics: GuiGraphics, cx: Int, cy: Int, half: Int) {
        for (blip in blips.values) {
            val alpha = ((1f - (ticks - blip.lastHitTick).toFloat() / FADE_TICKS).coerceIn(0f, 1f) * 255).toInt()
            if (alpha <= 0 || blip.lastHitTick < 0) continue

            val (px, py) = blipScreenPos(blip, cx, cy, half)

            val dotColor = (alpha shl 24) or iffColor(blip.friendly)
            drawContactIcon(guiGraphics, px, py, blip.kind, dotColor)
            // Взятая на сопровождение отметка обводится рамкой — чтобы было
            // видно, какой именно контакт сейчас ведёт ЗРК.
            // Сравнение по сетевому id, а не по ссылке: у загоризонтной копии
            // и настоящей цели id одинаковый, а какая из них сейчас лежит в
            // захвате — зависит от того, прогружена ли цель.
            val lockedId = (ClientEventHandler.lockingEntityVehicle ?: ClientEventHandler.seekingEntityVehicle)?.id
            if (lockedId != null && lockedId == blip.entity.id) {
                drawSelectionBox(guiGraphics, px, py, (alpha shl 24) or 0xFFFFFF)
            }
            // Same short numeric ID the Азимут-Высота indicator uses, not
            // the entity's full display name — matches a real search
            // radar's target-number readout instead of naming contacts.
            guiGraphics.drawCenteredString(this.font, blip.displayId.toString(), px, py - 14, (alpha shl 24) or 0xFFFFFF)
        }
    }

    // Rectangular азимут(0-360°)/высота scope — same relative-to-turret
    // bearing the ИКО circle uses (0° = dead ahead), so a contact lines up
    // at the same horizontal position across both displays. Height is the
    // raw block delta above the vehicle (this mod has no real "km" scale to
    // draw from, so the axis is just labeled in blocks).
    private fun drawAzimuthHeightIndicator(guiGraphics: GuiGraphics, left: Int, top: Int, right: Int, bottom: Int) {
        val panelColor = 0xE0041A0A.toInt()
        val gridColor = 0xFF1E6B2E.toInt()
        val textColor = 0x39FF6A

        guiGraphics.fill(left, top, right, bottom, panelColor)

        val width = right - left
        val height = bottom - top

        var az = 0
        while (az <= 360) {
            val x = left + (az / 360f * width).toInt()
            guiGraphics.fill(x, top, x + 1, bottom, gridColor)
            if (az < 360) {
                val label = az.toString()
                guiGraphics.drawString(this.font, label, x - this.font.width(label) / 2, bottom + 2, textColor)
            }
            az += 60
        }
        guiGraphics.drawString(this.font, "Аз", right - 12, bottom + 2, textColor)

        for (i in 0..RING_COUNT) {
            val y = bottom - (i.toFloat() / RING_COUNT * height).toInt()
            guiGraphics.fill(left, y, right, y + 1, gridColor)
            val label = ((i.toFloat() / RING_COUNT) * ahMaxHeight).toInt().toString()
            guiGraphics.drawString(this.font, label, left - this.font.width(label) - 3, y - this.font.lineHeight / 2, textColor)
        }
        guiGraphics.drawString(this.font, "m", left - 12, top - 10, textColor)

        for (blip in blips.values) {
            val alpha = ((1f - (ticks - blip.lastHitTick).toFloat() / FADE_TICKS).coerceIn(0f, 1f) * 255).toInt()
            if (alpha <= 0 || blip.lastHitTick < 0) continue

            val relBearing = relativeBearing(blip.bearing)
            val azNorm = if (relBearing < 0f) relBearing + 360f else relBearing
            val px = left + (azNorm / 360f * width).toInt()
            val heightFraction = (blip.height / ahMaxHeight).coerceIn(0.0, 1.0)
            val py = bottom - (heightFraction * height).toInt()

            val dotColor = (alpha shl 24) or iffColor(blip.friendly)
            drawContactIcon(guiGraphics, px, py, blip.kind, dotColor)
            guiGraphics.drawString(this.font, blip.displayId.toString(), px + 7, py - 4, (alpha shl 24) or 0xFFFFFF)
        }
    }

    // Отметка контакта. Иконка зависит только от рода цели, без дробления на
    // подтипы: самолёт — треугольник, вертолёт — «винт» (крест с втулкой),
    // ракета (любая, включая снаряды и бомбы) — ромб. Все три рисуются
    // вписанными в один и тот же квадрат ICON_HALF, поэтому одинаково читаются
    // и на круговом ИКО, и на индикаторе азимут-высота.
    private fun drawContactIcon(guiGraphics: GuiGraphics, cx: Int, cy: Int, kind: ContactKind, color: Int) {
        when (kind) {
            ContactKind.AIRPLANE -> fillTriangleUp(guiGraphics, cx, cy, ICON_HALF, color)
            ContactKind.MISSILE -> fillDiamond(guiGraphics, cx, cy, ICON_HALF, color)
            ContactKind.HELICOPTER -> {
                for (i in -ICON_HALF..ICON_HALF) {
                    guiGraphics.fill(cx + i, cy + i, cx + i + 1, cy + i + 1, color)
                    guiGraphics.fill(cx + i, cy - i, cx + i + 1, cy - i + 1, color)
                }
                guiGraphics.fill(cx - 1, cy - 1, cx + 2, cy + 2, color)
            }
        }
    }

    // Ромб: ширина строки линейно убывает от центра к вершинам.
    private fun fillDiamond(guiGraphics: GuiGraphics, cx: Int, cy: Int, halfSize: Int, color: Int) {
        for (dy in -halfSize..halfSize) {
            val rowWidth = halfSize - abs(dy)
            guiGraphics.fill(cx - rowWidth, cy + dy, cx + rowWidth + 1, cy + dy + 1, color)
        }
    }

    // Уголки вокруг отметки, взятой на сопровождение.
    private fun drawSelectionBox(guiGraphics: GuiGraphics, cx: Int, cy: Int, color: Int) {
        val r = ICON_HALF + 3
        val len = 3
        for (sx in intArrayOf(-1, 1)) {
            for (sy in intArrayOf(-1, 1)) {
                val x = cx + sx * r
                val y = cy + sy * r
                guiGraphics.fill(minOf(x, x - sx * len), y, maxOf(x, x - sx * len) + 1, y + 1, color)
                guiGraphics.fill(x, minOf(y, y - sy * len), x + 1, maxOf(y, y - sy * len) + 1, color)
            }
        }
    }

    // Small filled upward-pointing triangle (airborne-contact icon) — apex
    // at (cx, cy - halfSize), widening to 2*halfSize across at the base.
    private fun fillTriangleUp(guiGraphics: GuiGraphics, cx: Int, cy: Int, halfSize: Int, color: Int) {
        for (dy in 0..halfSize * 2) {
            val rowWidth = (dy.toFloat() / (halfSize * 2) * halfSize).toInt()
            val y = cy - halfSize + dy
            guiGraphics.fill(cx - rowWidth, y, cx + rowWidth + 1, y + 1, color)
        }
    }

    private fun iffColor(friendly: Boolean): Int = if (friendly) 0x39FF6A else 0xFF5252

    // Tabular target readout on the right — one row per currently-visible
    // contact (same visibility rule as the other two displays: still
    // fading in from its last sweep hit). SeekTool.IS_FRIENDLY (team,
    // ownership, current/last driver) drives the IFF column, same check
    // FriendlyVehicleMarkerOverlay uses for its own friend/foe triangles.
    private fun drawTargetList(guiGraphics: GuiGraphics, left: Int, top: Int, right: Int, bottom: Int) {
        val panelColor = 0xE0141414.toInt()
        val gridColor = 0xFF555555.toInt()
        val textColor = 0xAAAAAA

        guiGraphics.fill(left, top, right, bottom, panelColor)

        val width = right - left
        val colId = left + 4
        val colAz = left + (width * 0.14f).toInt()
        val colD = left + (width * 0.30f).toInt()
        val colH = left + (width * 0.44f).toInt()
        val colType = left + (width * 0.58f).toInt()
        val colIff = left + (width * 0.82f).toInt()
        val typeMaxWidth = colIff - colType - 4

        guiGraphics.drawString(this.font, "#", colId, top + 2, textColor)
        guiGraphics.drawString(this.font, "Аз", colAz, top + 2, textColor)
        guiGraphics.drawString(this.font, "Д", colD, top + 2, textColor)
        guiGraphics.drawString(this.font, "Н", colH, top + 2, textColor)
        guiGraphics.drawString(this.font, "Тип", colType, top + 2, textColor)
        guiGraphics.drawString(this.font, "IFF", colIff, top + 2, textColor)
        val headerY = top + this.font.lineHeight + 4
        guiGraphics.fill(left, headerY, right, headerY + 1, gridColor)

        var rowY = headerY + 4
        val rowHeight = this.font.lineHeight + 2
        // No gradual fade here, unlike the ИКО/AH blips — but the row still
        // needs to go dark in step with the dot, not just track `tracked`
        // (which only reflects range/height/LOS, not the sweep — a
        // stationary in-range target stays "tracked" forever even while its
        // dot has visibly gone dark between sweep passes). Using the exact
        // same alpha formula the dot uses and a near-zero cutoff instead of
        // requiring alpha==0 outright: with SWEEP_HALF_WIDTH's hit window
        // and the sweep's own rotation period, a fixed-bearing target's
        // longest gap between hits lands a few ticks SHORT of FADE_TICKS,
        // so the dot only ever dims to a barely-visible sliver rather than
        // literally hitting zero alpha — an exact `>= FADE_TICKS` check
        // would then never fire and the row would never hide at all.
        for (blip in blips.values) {
            val alpha = ((1f - (ticks - blip.lastHitTick).toFloat() / FADE_TICKS).coerceIn(0f, 1f) * 255).toInt()
            if (!blip.tracked || blip.lastHitTick < 0 || alpha < ALPHA_VISIBLE_THRESHOLD) continue
            if (rowY + rowHeight > bottom) break

            val color = 0xFFFFFFFF.toInt()
            val iff = 0xFF000000.toInt() or iffColor(blip.friendly)

            guiGraphics.drawString(this.font, blip.displayId.toString(), colId, rowY, color)
            guiGraphics.drawString(this.font, relativeBearing(blip.bearing).toInt().toString(), colAz, rowY, color)
            guiGraphics.drawString(this.font, blip.range.toInt().toString(), colD, rowY, color)
            guiGraphics.drawString(this.font, blip.height.toInt().toString(), colH, rowY, color)
            var typeText = blip.typeName
            while (typeText.isNotEmpty() && this.font.width(typeText) > typeMaxWidth) {
                typeText = typeText.dropLast(1)
            }
            guiGraphics.drawString(this.font, typeText, colType, rowY, color)
            guiGraphics.drawString(this.font, if (blip.friendly) "Свой" else "Чужой", colIff, rowY, iff)

            rowY += rowHeight
        }
    }

    companion object {
        // Совпадает с SeekRange ракеты 57Э6 в pantsir_s1.json: ровно на этой
        // дальности сервер и отдаёт загоризонтные контакты, так что показывать
        // меньше — терять уже полученные цели
        private const val RANGE = 1680.0

        // Радиус выборки сущностей ИЗ САМОГО клиентского мира. Намеренно
        // меньше RANGE: запрос по AABB перебирает секции в коробке, и на
        // 1400 блоках это сотни тысяч проверок каждый тик, дважды. Смысла в
        // них нет — дальше зоны прогруза сущностей на клиенте не бывает в
        // принципе, всё остальное приходит готовым списком из радарной
        // синхронизации. Отсечение по настоящей дальности (RANGE) делается
        // ниже, уже по расстоянию.
        private const val LOCAL_QUERY_RANGE = 512.0
        private const val MIN_TARGET_HEIGHT = 10.0
        private const val AH_MIN_SCALE = 100.0
        private const val AH_HEIGHT_STEP = 100.0
        private const val RING_COUNT = 4
        private const val ICON_HALF = 4

        // Сколько «прозрачных» клеток луч переживает. Одно дерево или забор
        // радару не помеха, семь подряд — уже сплошная преграда.
        private const val SOFT_COVER_LIMIT = 7
        private const val CLICK_RADIUS = 12.0
        private const val SWEEP_HALF_WIDTH = 6f
        // Один оборот антенны при PantsirEntity.RADAR_SPIN_SPEED = 4.5 град/тик.
        // Отметка гаснет ровно за оборот, поэтому эти два числа связаны: если
        // менять скорость вращения, менять и здесь.
        private const val FADE_TICKS = 80
        // Below this the dot is already imperceptibly dim (out of 255) —
        // used to hide the target-list row in step with it.
        private const val ALPHA_VISIBLE_THRESHOLD = 30
    }
}
