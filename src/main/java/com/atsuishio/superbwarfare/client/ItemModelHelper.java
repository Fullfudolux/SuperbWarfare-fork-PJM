package com.atsuishio.superbwarfare.client;

import com.atsuishio.superbwarfare.data.gun.GunData;
import com.atsuishio.superbwarfare.data.gun.subdata.Attachment;
import com.atsuishio.superbwarfare.data.gun.value.AttachmentType;
import net.minecraft.world.item.ItemStack;
import software.bernie.geckolib.cache.object.GeoBone;

public class ItemModelHelper {

    public static void handleGunAttachments(GeoBone bone, ItemStack stack, String name) {
        var attachments = GunData.from(stack).attachment;

        splitBoneName(bone, name, attachments, AttachmentType.SCOPE);
        splitBoneName(bone, name, attachments, AttachmentType.MAGAZINE);
        splitBoneName(bone, name, attachments, AttachmentType.BARREL);
        splitBoneName(bone, name, attachments, AttachmentType.STOCK);
        splitBoneName(bone, name, attachments, AttachmentType.GRIP);
        splitBoneName(bone, name, GunData.from(stack).selectedAmmoType.get());
    }

    // Trailing-integer suffix of boneName, or -1 if boneName is not exactly [non-digits][digits].
    // Replaces boneName.split("(?<=\\D)(?=\\d)") (regex + String[] alloc per call, up to 12x per bone per frame).
    private static int trailingIndex(String boneName) {
        int len = boneName.length();
        int digitStart = len;
        while (digitStart > 0 && Character.isDigit(boneName.charAt(digitStart - 1))) digitStart--;
        if (digitStart == 0 || digitStart == len) return -1;
        for (int j = 0; j < digitStart; j++) {
            if (Character.isDigit(boneName.charAt(j))) return -1;
        }
        try {
            return Integer.parseInt(boneName.substring(digitStart));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static void splitBoneName(GeoBone bone, String boneName, Attachment attachment, AttachmentType type) {
        if (boneName.startsWith(type.getAttachmentName())) {
            int index = trailingIndex(boneName);
            if (index >= 0) bone.setHidden(attachment.get(type) != index);
        }
    }

    private static void splitBoneName(GeoBone bone, String boneName, int ammoType) {
        if (boneName.startsWith("AmmoType")) {
            int index = trailingIndex(boneName);
            if (index >= 0) bone.setHidden(ammoType != index);
        }
    }

    public static void hideAllAttachments(GeoBone bone, String name) {
        splitAndHideBone(bone, name, "Scope");
        splitAndHideBone(bone, name, "Magazine");
        splitAndHideBone(bone, name, "Barrel");
        splitAndHideBone(bone, name, "Stock");
        splitAndHideBone(bone, name, "Grip");
        splitAndHideBoneAmmoType(bone, name);
    }

    private static void splitAndHideBone(GeoBone bone, String boneName, String tagName) {
        if (boneName.startsWith(tagName)) {
            int index = trailingIndex(boneName);
            if (index >= 0) bone.setHidden(index != 0);
        }
    }

    private static void splitAndHideBoneAmmoType(GeoBone bone, String boneName) {
        if (boneName.startsWith("AmmoType")) {
            int index = trailingIndex(boneName);
            if (index >= 0) bone.setHidden(index != 0);
        }
    }
}
