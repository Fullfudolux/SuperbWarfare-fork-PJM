package com.atsuishio.superbwarfare.network.message.send

import com.atsuishio.superbwarfare.entity.vehicle.Cv90Entity
import com.atsuishio.superbwarfare.network.PayloadContext
import com.atsuishio.superbwarfare.network.ServerPacketPayload
import kotlinx.serialization.Serializable

/** Driver-only toggle for the CV-90's rear troop ramp. */
@Serializable
data object ToggleVehicleRampMessage : ServerPacketPayload() {
    override fun PayloadContext.handler() {
        val player = sender()
        val vehicle = player.vehicle as? Cv90Entity ?: return
        if (vehicle.getSeatIndex(player) != 0) return

        vehicle.rampOpen = !vehicle.rampOpen
    }
}
