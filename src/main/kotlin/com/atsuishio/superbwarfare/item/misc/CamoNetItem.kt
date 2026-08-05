package com.atsuishio.superbwarfare.item.misc

import com.atsuishio.superbwarfare.entity.vehicle.AjaxEntity
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.item.IVehicleInteract
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Rarity

// PJM: навесная маскировочная сеть. ПКМ по машине — накинуть, ПКМ ещё раз — снять.
class CamoNetItem : Item(Properties().stacksTo(16).rarity(Rarity.RARE)), IVehicleInteract {

    override fun onInteractVehicle(
        vehicle: VehicleEntity,
        stack: ItemStack,
        player: Player,
        hand: InteractionHand
    ): InteractionResult? {
        if (vehicle !is AjaxEntity) return null

        if (!player.level().isClientSide) {
            if (vehicle.hasCamoNet) {
                vehicle.hasCamoNet = false
                if (!player.addItem(ItemStack(this))) player.drop(ItemStack(this), false)
                player.displayClientMessage(
                    Component.translatable("tips.superbwarfare.camo_net.removed")
                        .withStyle(ChatFormatting.GRAY), true
                )
            } else {
                vehicle.hasCamoNet = true
                stack.shrink(1)
                player.displayClientMessage(
                    Component.translatable("tips.superbwarfare.camo_net.installed")
                        .withStyle(ChatFormatting.GREEN), true
                )
            }
        }
        return InteractionResult.sidedSuccess(player.level().isClientSide)
    }
}
