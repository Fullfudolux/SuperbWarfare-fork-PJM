package com.atsuishio.superbwarfare.client.overlay

import com.atsuishio.superbwarfare.Mod.Companion.loc
import com.atsuishio.superbwarfare.client.RenderHelper
import com.atsuishio.superbwarfare.client.overlay.VehicleHudOverlay.renderKillIndicator
import com.atsuishio.superbwarfare.client.overlay.VehicleHudOverlay.renderKillIndicatorDynamic
import com.atsuishio.superbwarfare.client.overlay.VehicleMainWeaponHudOverlay.renderWeaponInfoThird
import com.atsuishio.superbwarfare.client.overlay.weapon.LandVehicleHud
import com.atsuishio.superbwarfare.data.gun.GunProp
import com.atsuishio.superbwarfare.data.vehicle.subdata.VehicleType
import com.atsuishio.superbwarfare.entity.vehicle.PantsirEntity
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.event.ClientEventHandler
import com.atsuishio.superbwarfare.init.ModKeyMappings
import com.atsuishio.superbwarfare.tools.*
import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.math.Axis
import net.minecraft.client.CameraType
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.Vec3
import net.neoforged.api.distmarker.Dist
import net.neoforged.api.distmarker.OnlyIn
import org.joml.Math

@OnlyIn(Dist.CLIENT)
object VehicleCrosshairOverlay : CommonOverlay("vehicle_crosshair") {

    private val LOGGER = ResourceOnceLogger()

    val CROSSHAIR_MAP = mapOf(
        "@VehicleUsApc" to loc("textures/overlay/vehicle/crosshair/us_apc.png"),
        "@VehicleUsTank" to loc("textures/overlay/vehicle/crosshair/us_tank.png"),
        "@VehicleRuApc" to loc("textures/overlay/vehicle/crosshair/ru_apc.png"),
        "@VehicleCnTank" to loc("textures/overlay/vehicle/crosshair/cn_tank.png"),
        "@VehicleCommonMissile" to loc("textures/overlay/vehicle/crosshair/common_missile.png"),
        "@VehicleCommonSeekMissile" to loc("textures/overlay/vehicle/crosshair/common_seek_missile.png"),
        "@VehicleCommonGun" to loc("textures/overlay/vehicle/crosshair/common_gun.png"),
        "@VehicleCommonGunDynamic" to loc("textures/overlay/vehicle/crosshair/common_gun.png"),
        "@VehicleCommonCannon" to loc("textures/overlay/vehicle/crosshair/common_cannon.png"),
        "@VehicleCommonCross" to loc("textures/overlay/vehicle/crosshair/common_cross.png"),
        "@VehicleDynamicCross" to loc("textures/overlay/vehicle/crosshair/common_dynamic_cross.png"),
        "@VehicleFixedPoint" to loc("textures/overlay/vehicle/crosshair/common_fixed_point.png"),
        "@VehicleCnHpjZooming" to loc("textures/overlay/vehicle/crosshair/cn_hpj_zooming.png"),
        "@VehicleCommonCannonZooming" to loc("textures/overlay/vehicle/crosshair/common_cannon_zooming.png"),
        "@VehicleLaserCannon" to loc("textures/overlay/vehicle/crosshair/laser_cannon.png"),
        "@AirCraftCommon" to loc("textures/overlay/vehicle/aircraft/common.png"),
        "@AirCraftNacelle" to loc("textures/overlay/vehicle/crosshair/nacelle.png"),
        "@NoCross" to loc("textures/overlay/vehicle/crosshair/empty.png")
    )

    private val CROSSHAIR_THIRD_CAMERA = loc("textures/overlay/vehicle/crosshair/third_camera.png")
    private var scopeScale = 1f

    override fun shouldRender(): Boolean {
        val shouldRender = super.shouldRender()
        if (!shouldRender) {
            resetScale()
        }
        return shouldRender
    }

    override fun RenderContext.render() {
        val entity = player.vehicle
        if (entity !is VehicleEntity) {
            resetScale()
            return
        }

        val index = entity.getSeatIndex(player)
        val data = entity.getGunData(index)
        if (data == null) {
            resetScale()
            return
        }

        val poseStack = guiGraphics.pose()

        var crosshairPath = data.get(GunProp.CROSSHAIR)

        if (crosshairPath == CrossHairOverlay.CROSSHAIR_EMPTY) {
            resetScale()
            return
        }

        if (ClientEventHandler.zoomVehicle && data.get(GunProp.CROSSHAIR_ZOOMING) != CrossHairOverlay.CROSSHAIR_EMPTY) {
            crosshairPath = data.get(GunProp.CROSSHAIR_ZOOMING)
        }

        // Target box + speed/altitude readout — drawn regardless of camera
        // mode (first-person, zoomed, or third-person), unlike the crosshair
        // texture itself below which only renders in the first-person/zoom
        // branch. Was previously nested inside that branch only, so it never
        // showed up at all in third-person view (Pantsir's gunner seat uses
        // a fixed scope-style camera that isn't necessarily CameraType.FIRST_PERSON).
        //
        // Keyed off the CURRENTLY SELECTED weapon actually having lock
        // capability, not a specific crosshair texture — the Cannon has its
        // own SeekWeaponInfo now too (same lock/lead-aim system as the
        // Missile), so this needs to show for either, not just the missile.
        if (entity is PantsirEntity && data.get(GunProp.SEEK_WEAPON_INFO)?.onlyLockEntity == true) {
            val target = ClientEventHandler.lockingEntityVehicle ?: ClientEventHandler.seekingEntityVehicle
            if (target != null && target.isAlive) {
                drawTargetBox(guiGraphics, target, partialTick, ClientEventHandler.lockOnVehicle)
                // Cannon doesn't auto-aim (PantsirEntity.adjustTurretAngle
                // only does that for the Missile) — CalculateTrajectory is
                // only set true on the Cannon's own SeekWeaponInfo, so this
                // is effectively "cannon selected AND fully locked", giving
                // the gunner a lead-point marker to walk the reticle onto
                // by hand instead.
                if (ClientEventHandler.lockOnVehicle && data.get(GunProp.SEEK_WEAPON_INFO)?.calculateTrajectory == true) {
                    drawLeadMarker(guiGraphics, entity, player, target, partialTick)
                }
            }
        }

        val color = data.get(GunProp.CROSSHAIR_COLOR).get()

        poseStack.pushPose()

        val recoil = Mth.lerp(partialTick, entity.recoilShakeO.toFloat(), entity.recoilShake.toFloat())
        val pitch = Mth.lerp(partialTick, entity.fakePitchO, entity.fakePitch)
        poseStack.translate(
            LandVehicleHud.lerpRecoil * 6 + screenWidth * 0.025f * recoil,
            recoil * 3 + screenHeight * 0.025f * recoil - pitch,
            0f
        )
        poseStack.scale(1 - recoil * 0.05f, 1 - recoil * 0.05f, 1f)
        poseStack.rotateAround(
            Axis.ZP.rotationDegrees(-0.3f * ClientEventHandler.cameraRoll + 4 * LandVehicleHud.lerpRecoil),
            screenWidth / 2f,
            screenHeight / 2f,
            0f
        )

        RenderSystem.disableDepthTest()
        RenderSystem.depthMask(false)
        RenderSystem.enableBlend()
        RenderSystem.setShader { GameRenderer.getPositionTexShader() }
        RenderSystem.blendFuncSeparate(
            GlStateManager.SourceFactor.SRC_ALPHA,
            GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
            GlStateManager.SourceFactor.ONE,
            GlStateManager.DestFactor.ZERO
        )
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f)

        scopeScale = Mth.lerp(partialTick, scopeScale, 1f)
        val scale: Float = scopeScale

        var shootPos = entity.getShootPosForHud(player, partialTick)
        var shootVec = entity.getShootDirectionForHud(player, partialTick).scale(512.0)
        val nacelleCam = ClientEventHandler.isNacelleCam(player)

        if (nacelleCam) {
            shootPos = camera.position
            shootVec = Vec3(camera.lookVector).scale(512.0)
        }

        val result = player.level().clip(
            ClipContext(
                shootPos, shootPos.add(shootVec),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, player
            )
        )

        val hitPos = result.location

        var dis = shootPos.distanceTo(hitPos)

        var lookingEntity = entity.getPlayerLookAtEntityOnVehicle(player, 512.0, partialTick)
        if (nacelleCam) {
            lookingEntity = TraceTool.camerafFindLookingEntity(player, shootPos, shootVec, 512.0)
        }

        if (lookingEntity != null) {
            dis = shootPos.distanceTo(lookingEntity.position())
        }

        val pos = shootPos.add(entity.getShootDirectionForHud(player, partialTick).scale(dis))
        val p = pos.worldToScreen()

        // 渲染第一人称
        if (Minecraft.getInstance().options.cameraType == CameraType.FIRST_PERSON || ClientEventHandler.zoomVehicle) {
            poseStack.pushPose()

            val texture: ResourceLocation?
            if (crosshairPath.startsWith("@")) {
                texture = CROSSHAIR_MAP.get(crosshairPath)
            } else {
                texture = ResourceLocation.tryParse(crosshairPath)
            }

            if (texture == null) {
                val finalCrosshairPath = crosshairPath
                if (finalCrosshairPath != "@Custom") {
                    LOGGER.log(
                        crosshairPath
                    ) { logger ->
                        logger.error(
                            "Failed to load crosshair texture for {}",
                            finalCrosshairPath
                        )
                    }
                }
            } else {
                val minWH = Math.min(screenWidth, screenHeight).toFloat()
                val scaledMinWH = Mth.floor(minWH * scale).toFloat()
                val centerW = (screenWidth - scaledMinWH) / 2
                val centerH = (screenHeight - scaledMinWH) / 2
                val x = p.x.toFloat()
                val y = p.y.toFloat()

                if (crosshairPath == "@VehicleDynamicCross" && pos.canBeSeen()) {
                    RenderHelper.preciseBlitWithColor(
                        guiGraphics,
                        texture,
                        x - scaledMinWH / 2,
                        y - scaledMinWH / 2,
                        0f,
                        0f,
                        scaledMinWH,
                        scaledMinWH,
                        scaledMinWH,
                        scaledMinWH,
                        color
                    )
                    renderKillIndicatorDynamic(
                        guiGraphics,
                        x - 7.5f + (2 * (Math.random() - 0.5f)).toFloat(),
                        y - 7.5f + (2 * (Math.random() - 0.5f)).toFloat()
                    )
                    val fixedTexture: ResourceLocation? = CROSSHAIR_MAP["@VehicleFixedPoint"]
                    RenderHelper.preciseBlitWithColor(
                        guiGraphics,
                        fixedTexture,
                        centerW,
                        centerH,
                        0f,
                        0f,
                        scaledMinWH,
                        scaledMinWH,
                        scaledMinWH,
                        scaledMinWH,
                        color
                    )
                } else if ((crosshairPath == "@AirCraftCommon" || crosshairPath == "@VehicleLaserCannon" || crosshairPath == "@VehicleCommonGunDynamic") && pos.canBeSeen()) {
                    RenderHelper.preciseBlitWithColor(
                        guiGraphics,
                        texture,
                        x - scaledMinWH / 2,
                        y - scaledMinWH / 2,
                        0f,
                        0f,
                        scaledMinWH,
                        scaledMinWH,
                        scaledMinWH,
                        scaledMinWH,
                        color
                    )
                    renderKillIndicatorDynamic(
                        guiGraphics,
                        x - 7.5f + (2 * (Math.random() - 0.5f)).toFloat(),
                        y - 7.5f + (2 * (Math.random() - 0.5f)).toFloat()
                    )
                } else if (crosshairPath == "@AirCraftNacelle") {
                    RenderHelper.preciseBlitWithColor(
                        guiGraphics,
                        texture,
                        centerW,
                        centerH,
                        0f,
                        0f,
                        scaledMinWH,
                        scaledMinWH,
                        scaledMinWH,
                        scaledMinWH,
                        color
                    )
                    renderKillIndicator(guiGraphics, screenWidth.toFloat(), screenHeight.toFloat())

                    val width = Minecraft.getInstance().font.width(FormatTool.format0D(dis, " m"))
                    guiGraphics.drawString(
                        Minecraft.getInstance().font,
                        Component.literal(FormatTool.format0D(dis, " m")),
                        screenWidth / 2 - width / 2,
                        screenHeight / 2 + 30,
                        color,
                        false
                    )

                    val heat = entity.getWeaponHeat(player)
                    val component = entity.firstPersonAmmoComponent(data, player)

                    guiGraphics.drawString(
                        font, component, (screenWidth) / 2 - 50 - font.width(component), screenHeight / 2 + 2,
                        MathTool.getGradientColor(color, 0xFF0000, heat, 2), false
                    )

                } else if (crosshairPath == "@VehicleCnHpjZooming") {
                    val dynamicTexture: ResourceLocation? = CROSSHAIR_MAP["@VehicleDynamicCross"]
                    RenderHelper.preciseBlitWithColor(
                        guiGraphics,
                        dynamicTexture,
                        x - scaledMinWH / 2,
                        y - scaledMinWH / 2,
                        0f,
                        0f,
                        scaledMinWH,
                        scaledMinWH,
                        scaledMinWH,
                        scaledMinWH,
                        color
                    )
                    renderKillIndicatorDynamic(
                        guiGraphics,
                        x - 7.5f + (2 * (Math.random() - 0.5f)).toFloat(),
                        y - 7.5f + (2 * (Math.random() - 0.5f)).toFloat()
                    )
                } else if (crosshairPath == "@VehicleCommonCannonZooming") {
                    val fovAdjust = 60f / Minecraft.getInstance().options.fov().get()
                    val f = Math.min(screenWidth, screenHeight).toFloat()
                    val f1 = Math.min(screenWidth.toFloat() / f, screenHeight.toFloat() / f) * fovAdjust
                    val i = Mth.floor(f * f1)
                    val j = Mth.floor(f * f1)
                    val k = (screenWidth - i) / 2
                    val l = (screenHeight - j) / 2
                    RenderHelper.preciseBlit(
                        guiGraphics,
                        texture,
                        k.toFloat(),
                        l.toFloat(),
                        0f,
                        0f,
                        i.toFloat(),
                        j.toFloat(),
                        i.toFloat(),
                        j.toFloat()
                    )
                    renderKillIndicator(guiGraphics, screenWidth.toFloat(), screenHeight.toFloat())
                } else if (crosshairPath == "@VehicleCommonSeekMissile" && data.get(GunProp.SEEK_WEAPON_INFO) != null && data.get(
                        GunProp.SEEK_WEAPON_INFO
                    )?.onlyLockBlock ?: false
                ) {
                    var vec3 = ClientEventHandler.seekingPosVehicle
                    if (ClientEventHandler.seekingTimeVehicle > 0) {
                        vec3 = ClientEventHandler.lockingPosVehicle
                    }
                    if (vec3 != null) {
                        val string = vec3.toFormattedString()
                        val width = Minecraft.getInstance().font.width(string)
                        RenderHelper.preciseBlitWithColor(
                            guiGraphics,
                            texture,
                            centerW,
                            centerH,
                            0f,
                            0f,
                            scaledMinWH,
                            scaledMinWH,
                            scaledMinWH,
                            scaledMinWH,
                            color
                        )
                        guiGraphics.drawString(
                            Minecraft.getInstance().font,
                            string,
                            screenWidth.toFloat() / 2 - width.toFloat() / 2,
                            screenHeight.toFloat() - 73,
                            color,
                            false
                        )
                    }
                } else {
                    RenderHelper.preciseBlitWithColor(
                        guiGraphics,
                        texture,
                        centerW,
                        centerH,
                        0f,
                        0f,
                        scaledMinWH,
                        scaledMinWH,
                        scaledMinWH,
                        scaledMinWH,
                        color
                    )
                    renderKillIndicator(guiGraphics, screenWidth.toFloat(), screenHeight.toFloat())
                }
            }

            poseStack.popPose()
        } else if (Minecraft.getInstance().options.cameraType == CameraType.THIRD_PERSON_BACK && !ClientEventHandler.zoomVehicle) {
            val seekInfo = data.get(GunProp.SEEK_WEAPON_INFO)
            val flag = seekInfo != null && seekInfo.inputBlockPos
            // 渲染第三人称
            if (!flag && pos.canBeSeen() && !((entity.vehicleType == VehicleType.AIRPLANE || entity.vehicleType == VehicleType.HELICOPTER || data.get(GunProp.CROSSHAIR) == "@AirBomb") && player === entity.getFirstPassenger())) {
                val x = p.x.toFloat()
                val y = p.y.toFloat()

                RenderHelper.preciseBlit(
                    guiGraphics,
                    CROSSHAIR_THIRD_CAMERA,
                    x - 12,
                    y - 12,
                    0f,
                    0f,
                    24f,
                    24f,
                    24f,
                    24f
                )
                renderKillIndicatorDynamic(
                    guiGraphics,
                    x - 7.5f + (2 * (Math.random() - 0.5f)).toFloat(),
                    y - 7.5f + (2 * (Math.random() - 0.5f)).toFloat()
                )

                poseStack.pushPose()

                poseStack.translate(x, y, 0f)
                poseStack.scale(0.75f, 0.75f, 1f)

                renderWeaponInfoThird(guiGraphics, entity, player, data, mc.font)

                if (player === entity.getFirstPassenger() && entity.hasDecoy()) {
                    if (entity.decoyReady) {
                        guiGraphics.drawString(
                            Minecraft.getInstance().font,
                            Component.translatable("tips.superbwarfare.smoke.ready").append(
                                Component.literal(
                                    " [" + ModKeyMappings.RELEASE_DECOY.key.displayName.string + "]"
                                )
                            ),
                            30,
                            1,
                            -1,
                            false
                        )
                    } else {
                        guiGraphics.drawString(
                            Minecraft.getInstance().font,
                            Component.translatable("tips.superbwarfare.smoke.reloading"),
                            30,
                            1,
                            0xFF0000,
                            false
                        )
                    }
                }

                poseStack.popPose()
            }
        }

        poseStack.popPose()
    }

    private fun resetScale() {
        scopeScale = 0.7f
    }

    // Projects the target's ACTUAL world-space hitbox (not a fixed-size
    // icon) onto the screen and draws a square outline around it, with its
    // current speed and altitude written alongside — yellow while still
    // being acquired, red once fully locked.
    private fun drawTargetBox(guiGraphics: GuiGraphics, target: Entity, partialTick: Float, lockedOn: Boolean) {
        val center = VectorTool.lerpGetEntityBoundingBoxCenter(target, partialTick)
        if (!center.canBeSeen()) return

        val halfW = target.bbWidth / 2.0
        val halfH = target.bbHeight / 2.0

        // worldToScreen()'s .z is the pre-divide clip-space W (roughly the
        // view-space distance), NOT a normalized [0,1] depth — only "behind
        // the camera" (<= 0) is actually invalid, there is no upper bound.
        var minX = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        var anyInFront = false

        for (dx in doubleArrayOf(-halfW, halfW)) {
            for (dy in doubleArrayOf(-halfH, halfH)) {
                for (dz in doubleArrayOf(-halfW, halfW)) {
                    val screen = center.add(dx, dy, dz).worldToScreen()
                    if (screen.z <= 0.0) continue
                    anyInFront = true
                    if (screen.x < minX) minX = screen.x
                    if (screen.x > maxX) maxX = screen.x
                    if (screen.y < minY) minY = screen.y
                    if (screen.y > maxY) maxY = screen.y
                }
            }
        }

        if (!anyInFront) return

        val color = if (lockedOn) 0xFFFF3333.toInt() else 0xFFFFFF33.toInt()
        val x0 = minX.toInt() - 2
        val x1 = maxX.toInt() + 2
        val y0 = minY.toInt() - 2
        val y1 = maxY.toInt() + 2

        guiGraphics.fill(x0, y0, x1, y0 + 1, color)
        guiGraphics.fill(x0, y1 - 1, x1, y1, color)
        guiGraphics.fill(x0, y0, x0 + 1, y1, color)
        guiGraphics.fill(x1 - 1, y0, x1, y1, color)

        val font = Minecraft.getInstance().font
        val speed = FormatTool.format0D(target.deltaMovement.length() * 72, " km/h")
        val altitude = FormatTool.format0D(heightAboveGround(target).toDouble(), " m")

        // Label block sitting BELOW the box, both lines centered on the
        // box's own horizontal center (not left-aligned, which looked
        // ragged since "150 km/h" and "320 m" are different widths) —
        // speed on top, altitude right below it.
        val textScale = 0.7f
        val anchorX = (x0 + x1) / 2f
        val poseStack = guiGraphics.pose()
        poseStack.pushPose()
        poseStack.translate(anchorX.toDouble(), (y1 + 1).toDouble(), 0.0)
        poseStack.scale(textScale, textScale, 1f)
        guiGraphics.drawString(font, speed, -(font.width(speed) / 2f), 0f, color, false)
        guiGraphics.drawString(font, altitude, -(font.width(altitude) / 2f), font.lineHeight + 1f, color, false)
        poseStack.popPose()
    }

    // Where the Cannon actually needs to be pointed to hit the target given
    // its current velocity — same firing-solution math the auto-aimed
    // Missile uses (RangeTool.calculateFiringSolution), just projected to
    // screen space as a marker instead of physically turning the turret
    // there. A green ring at the lead point, connected to the target box's
    // own center by a line, matching a real search-radar CIWS-style lead
    // indicator.
    private fun drawLeadMarker(
        guiGraphics: GuiGraphics,
        vehicle: VehicleEntity,
        player: net.minecraft.world.entity.player.Player,
        target: Entity,
        partialTick: Float
    ) {
        val targetCenter = VectorTool.lerpGetEntityBoundingBoxCenter(target, partialTick)
        val shootPos = vehicle.getShootPos(player, partialTick)

        val aimVec = RangeTool.calculateFiringSolution(
            shootPos,
            targetCenter,
            target.deltaMovement,
            vehicle.getProjectileVelocity(player).toDouble(),
            vehicle.getProjectileGravity(player).toDouble()
        ).normalize()
        val leadPoint = shootPos.add(aimVec.scale(shootPos.distanceTo(targetCenter)))
        if (!leadPoint.canBeSeen()) return

        val targetScreen = targetCenter.worldToScreen()
        val leadScreen = leadPoint.worldToScreen()
        val color = 0xFF66FF00.toInt()

        drawLine(guiGraphics, targetScreen.x.toFloat(), targetScreen.y.toFloat(), leadScreen.x.toFloat(), leadScreen.y.toFloat(), color)
        drawMarkerRing(guiGraphics, leadScreen.x.toFloat(), leadScreen.y.toFloat(), 6f, color)
    }

    private fun drawLine(guiGraphics: GuiGraphics, x0: Float, y0: Float, x1: Float, y1: Float, color: Int) {
        val dx = x1 - x0
        val dy = y1 - y0
        val steps = kotlin.math.sqrt(dx * dx + dy * dy).toInt().coerceAtLeast(1)
        for (i in 0..steps) {
            val t = i / steps.toFloat()
            val x = (x0 + dx * t).toInt()
            val y = (y0 + dy * t).toInt()
            guiGraphics.fill(x, y, x + 1, y + 1, color)
        }
    }

    private fun drawMarkerRing(guiGraphics: GuiGraphics, cx: Float, cy: Float, radius: Float, color: Int) {
        val steps = (radius * 6).toInt().coerceAtLeast(24)
        for (i in 0 until steps) {
            val angle = 2.0 * Math.PI * i / steps
            val x = (cx + radius * kotlin.math.cos(angle)).toInt()
            val y = (cy + radius * kotlin.math.sin(angle)).toInt()
            guiGraphics.fill(x, y, x + 1, y + 1, color)
        }
    }

    // Blocks of clear air between the target and whatever solid ground/roof
    // is directly beneath it — an AGL-style reading, not the raw world Y
    // coordinate (which is just an absolute height above bedrock/build
    // limit and means nothing without knowing the local terrain).
    private fun heightAboveGround(entity: Entity): Int {
        val level = entity.level()
        val pos = entity.onPos
        val minY = level.minBuildHeight
        var height = 0
        while (true) {
            height++
            if (pos.y - height < minY) return height
            if (!level.getBlockState(pos.offset(0, -height, 0)).isAir) break
        }
        return height
    }
}
