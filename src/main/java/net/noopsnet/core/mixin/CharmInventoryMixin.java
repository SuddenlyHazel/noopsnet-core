// SPDX-License-Identifier: MPL-2.0
package net.noopsnet.core.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameRules;
import net.noopsnet.core.CozyProtection;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Retain inventory in place, including vanishing items, without changing XP or world rules. */
@Mixin(Player.class)
public abstract class CharmInventoryMixin {
    @WrapOperation(
            method = "dropEquipment",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/level/GameRules;"
                                        + "getBoolean(Lnet/minecraft/world/level/GameRules$Key;)Z"))
    private boolean noopsnetKeepCharmInventory(
            GameRules rules,
            GameRules.Key<GameRules.BooleanValue> key,
            Operation<Boolean> original) {
        return original.call(rules, key)
                || key == GameRules.RULE_KEEPINVENTORY
                        && CozyProtection.keepsInventory((Player) (Object) this);
    }
}
