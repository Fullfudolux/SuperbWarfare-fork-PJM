package com.atsuishio.superbwarfare.client.overlay

import net.minecraft.network.chat.Component

// Cached constant Components — Component.translatable/literal allocates MutableComponent per call;
// these are recreated per HUD frame when the overlay is active. Reuse one instance per string.
object HudComponents {
    val FLARE_RELOADING = Component.translatable("tips.superbwarfare.flare.reloading")
    val SMOKE_RELOADING = Component.translatable("tips.superbwarfare.smoke.reloading")
    val NO_POWER = Component.literal("NO POWER!")
    val LOW_POWER = Component.literal("LOW POWER")
    val DASH_M = Component.literal("---m")
    val DASH_M_CAP = Component.literal("---M")
}
