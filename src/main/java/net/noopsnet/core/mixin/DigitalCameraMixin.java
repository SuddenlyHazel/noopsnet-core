// SPDX-License-Identifier: MPL-2.0
package net.noopsnet.core.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Digital point-and-shoot mode: camera captures do not consume printing paper. */
@Pseudo
@Mixin(targets = "de.maxhenkel.camera.items.CameraItem", remap = false)
public abstract class DigitalCameraMixin {
    @Inject(method = "consumePaper", at = @At("HEAD"), cancellable = true, require = 1)
    private static void noopsnetDigitalCapture(CallbackInfoReturnable<Boolean> result) {
        result.setReturnValue(true);
    }
}
