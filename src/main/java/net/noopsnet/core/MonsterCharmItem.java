// SPDX-License-Identifier: MPL-2.0
package net.noopsnet.core;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;

import java.util.List;

public final class MonsterCharmItem extends Item {
    public static final int MAX_RADIUS = 32;

    public MonsterCharmItem(Properties properties) {
        super(properties);
    }

    static CompoundTag settings(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
    }

    static int radius(ItemStack stack) {
        CompoundTag tag = settings(stack);
        return tag.contains("radius") ? Math.clamp(tag.getInt("radius"), 1, MAX_RADIUS) : 8;
    }

    static boolean preventAttacks(ItemStack stack) {
        CompoundTag tag = settings(stack);
        return !tag.contains("prevent_attacks") || tag.getBoolean("prevent_attacks");
    }

    static boolean removeMonsters(ItemStack stack) {
        return settings(stack).getBoolean("remove_monsters");
    }

    static boolean preservePersistent(ItemStack stack) {
        CompoundTag tag = settings(stack);
        return !tag.contains("preserve_persistent") || tag.getBoolean("preserve_persistent");
    }

    static void configure(ItemStack stack, String key, int value) {
        CompoundTag tag = settings(stack);
        if (key.equals("radius")) tag.putInt(key, value);
        else tag.putBoolean(key, value != 0);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(
            Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide) {
            if (hand == InteractionHand.MAIN_HAND) {
                ItemStack previous = player.getOffhandItem();
                player.setItemInHand(InteractionHand.OFF_HAND, stack);
                player.setItemInHand(InteractionHand.MAIN_HAND, previous);
            }
            if (player instanceof ServerPlayer serverPlayer)
                CozyCommands.showCharm(serverPlayer, stack);
        }
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide);
    }

    @Override
    public void appendHoverText(
            ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.literal("Equip in your offhand for a cozy adventure."));
        lines.add(Component.literal("Keep your inventory, armor and charm on death."));
        lines.add(
                Component.literal(
                        "Hostile attack protection: " + (preventAttacks(stack) ? "on" : "off")));
        lines.add(
                Component.literal(
                        "Remove hostiles: "
                                + (removeMonsters(stack) ? "on" : "off")
                                + " ("
                                + radius(stack)
                                + " blocks)"));
        lines.add(Component.literal("Configure with /monstercharm"));
    }
}
