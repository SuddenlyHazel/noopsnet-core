// SPDX-License-Identifier: MPL-2.0
package net.noopsnet.core;

import com.mojang.authlib.GameProfile;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.UUID;

@GameTestHolder("noopsnet_core")
@PrefixGameTestTemplate(false)
public final class CharmDeathGameTests {
    private static ServerPlayer player(GameTestHelper h, GameProfile profile) {
        var player =
                new ServerPlayer(
                        h.getLevel().getServer(),
                        h.getLevel(),
                        profile,
                        ClientInformation.createDefault());
        player.connection = new FakePlayer(h.getLevel(), profile).connection;
        player.setPos(Vec3.atCenterOf(h.absolutePos(new BlockPos(2, 2, 2))));
        return player;
    }

    @GameTest(template = "empty")
    public static void offhandCharmKeepsInventoryThroughDeathAndRespawn(GameTestHelper h) {
        checkDeath(h, true, false);
    }

    @GameTest(template = "empty")
    public static void mainhandCharmStillDropsInventoryNormally(GameTestHelper h) {
        checkDeath(h, false, false);
    }

    @GameTest(template = "empty")
    public static void vanillaKeepInventoryStillWorksWithoutEquippedCharm(GameTestHelper h) {
        checkDeath(h, false, true);
    }

    private static void checkDeath(GameTestHelper h, boolean offhandCharm, boolean keepRule) {
        var level = h.getLevel();
        var rules = level.getGameRules();
        boolean previous = rules.getBoolean(GameRules.RULE_KEEPINVENTORY);
        boolean previousLoot = rules.getBoolean(GameRules.RULE_DOMOBLOOT);
        var profile = new GameProfile(UUID.randomUUID(), "CharmDeath");
        var original = player(h, profile);
        var respawned = player(h, profile);
        try {
            rules.getRule(GameRules.RULE_KEEPINVENTORY).set(keepRule, level.getServer());
            rules.getRule(GameRules.RULE_DOMOBLOOT).set(true, level.getServer());
            original.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 17));
            var tool = new ItemStack(Items.DIAMOND_PICKAXE);
            tool.setDamageValue(42);
            tool.set(DataComponents.CUSTOM_NAME, Component.literal("Keep this exact pickaxe"));
            tool.enchant(
                    level.registryAccess()
                            .lookupOrThrow(Registries.ENCHANTMENT)
                            .getOrThrow(Enchantments.VANISHING_CURSE),
                    1);
            original.getInventory().setItem(9, tool);
            original.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
            original.getInventory().selected = 3;
            var charm = new ItemStack(CozyProtection.CHARM.get());
            MonsterCharmItem.configure(charm, "prevent_attacks", 0);
            MonsterCharmItem.configure(charm, "radius", 13);
            original.setItemSlot(
                    offhandCharm ? EquipmentSlot.OFFHAND : EquipmentSlot.MAINHAND, charm);
            original.getInventory().selected = 3;
            original.experienceLevel = 12;
            original.totalExperience = 200;
            original.experienceProgress = .25F;
            var before = new ArrayList<ItemStack>();
            for (int slot = 0; slot < original.getInventory().getContainerSize(); slot++)
                before.add(original.getInventory().getItem(slot).copy());
            var dropArea = original.getBoundingBox().inflate(3);
            var previousDrops =
                    level.getEntitiesOfClass(ItemEntity.class, dropArea).stream()
                            .map(item -> item.getUUID())
                            .collect(java.util.stream.Collectors.toSet());
            original.setHealth(0);
            // Real ServerPlayer death, not FakePlayer's no-op die override.
            original.die(level.damageSources().fall());
            var dropped =
                    level.getEntitiesOfClass(
                            ItemEntity.class,
                            dropArea,
                            item -> !previousDrops.contains(item.getUUID()));
            boolean retained = offhandCharm || keepRule;
            h.assertTrue(
                    rules.getBoolean(GameRules.RULE_KEEPINVENTORY) == keepRule,
                    "Global keepInventory is unchanged");
            h.assertTrue(
                    retained == dropped.isEmpty(), "Only protected inventory avoids death drops");
            if (retained) {
                for (int slot = 0; slot < before.size(); slot++)
                    h.assertTrue(
                            ItemStack.matches(
                                    before.get(slot), original.getInventory().getItem(slot)),
                            "Death preserves original inventory in place, slot " + slot);
                // Normal player save data retains the inventory even if logout/restart precedes
                // respawn.
                CompoundTag saved = new CompoundTag();
                original.addAdditionalSaveData(saved);
                var reloaded = player(h, profile);
                try {
                    reloaded.readAdditionalSaveData(saved);
                    for (int slot = 0; slot < before.size(); slot++)
                        h.assertTrue(
                                ItemStack.matches(
                                        before.get(slot), reloaded.getInventory().getItem(slot)),
                                "Dead player's saved inventory survives reload, slot " + slot);
                } finally {
                    reloaded.discard();
                }
            } else {
                h.assertTrue(
                        dropped.stream()
                                .anyMatch(item -> item.getItem().is(CozyProtection.CHARM.get())),
                        "Unequipped charm drops normally");
                h.assertTrue(
                        dropped.stream().anyMatch(item -> item.getItem().is(Items.DIAMOND)),
                        "Normal death still drops items");
                h.assertTrue(
                        dropped.stream()
                                .noneMatch(item -> item.getItem().is(Items.DIAMOND_PICKAXE)),
                        "Normal vanishing curse still applies");
            }
            respawned.restoreFrom(original, false);
            if (retained) {
                for (int slot = 0; slot < before.size(); slot++)
                    h.assertTrue(
                            ItemStack.matches(
                                    before.get(slot), respawned.getInventory().getItem(slot)),
                            "Respawn restores each exact slot once, slot " + slot);
            } else
                h.assertTrue(
                        respawned.getInventory().isEmpty(),
                        "Normal respawn does not recreate dropped items");
            h.assertTrue(
                    respawned.experienceLevel == (keepRule ? 12 : 0),
                    "Charm inventory retention does not change vanilla XP rules");
            h.succeed();
        } finally {
            original.discard();
            respawned.discard();
            rules.getRule(GameRules.RULE_KEEPINVENTORY).set(previous, level.getServer());
            rules.getRule(GameRules.RULE_DOMOBLOOT).set(previousLoot, level.getServer());
        }
    }
}
