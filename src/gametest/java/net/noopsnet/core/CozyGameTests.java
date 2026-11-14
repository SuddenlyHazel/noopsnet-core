// SPDX-License-Identifier: MPL-2.0
package net.noopsnet.core;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.damagesource.DamageContainer;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.MobSpawnEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

@GameTestHolder("noopsnet_core")
@PrefixGameTestTemplate(false)
public final class CozyGameTests {
    private static MonsterStopperEntity stopper(GameTestHelper h) {
        BlockPos pos = h.absolutePos(new BlockPos(2, 2, 2));
        h.getLevel().setBlockAndUpdate(pos, CozyProtection.STOPPER.get().defaultBlockState());
        var stopper = (MonsterStopperEntity) h.getLevel().getBlockEntity(pos);
        stopper.radius = 4;
        // Block entity onLoad is queued; tests exercise the same load/unload hooks explicitly.
        stopper.onLoad();
        return stopper;
    }

    private static <T extends Mob> T mob(GameTestHelper h, EntityType<T> type, Vec3 position) {
        T mob = type.create(h.getLevel());
        mob.setPos(position);
        mob.setNoAi(true);
        return mob;
    }

    private static ServerPlayer player(GameTestHelper h) {
        var player =
                new FakePlayer(h.getLevel(), new GameProfile(UUID.randomUUID(), "CozyFixture"));
        player.setPos(Vec3.atCenterOf(h.absolutePos(new BlockPos(2, 2, 5))));
        return player;
    }

    private static LivingIncomingDamageEvent incoming(ServerPlayer player, DamageSource source) {
        return NeoForge.EVENT_BUS.post(
                new LivingIncomingDamageEvent(player, new DamageContainer(source, 4)));
    }

    @GameTest(template = "empty")
    public static void hostileSpawnsAndSphere(GameTestHelper h) {
        var stopper = stopper(h);
        Vec3 center = Vec3.atCenterOf(stopper.getBlockPos());
        var inside = mob(h, EntityType.ZOMBIE, center.add(4, 0, 0));
        h.assertTrue(
                !h.getLevel().addFreshEntity(inside),
                "Hostile spawn at spherical boundary blocked");
        var outside = mob(h, EntityType.ZOMBIE, center.add(4.01, 0, 0));
        h.assertTrue(h.getLevel().addFreshEntity(outside), "Just outside sphere allowed");
        var diagonal = mob(h, EntityType.ZOMBIE, center.add(3, 0, 3));
        h.assertTrue(
                h.getLevel().addFreshEntity(diagonal),
                "Bounding-box corner outside sphere allowed");
        var above = mob(h, EntityType.ZOMBIE, center.add(0, 4.01, 0));
        h.assertTrue(h.getLevel().addFreshEntity(above), "Radius includes vertical distance");
        h.assertTrue(
                h.getLevel().addFreshEntity(mob(h, EntityType.COW, center)),
                "Passive spawn allowed");
        var event =
                new MobSpawnEvent.PositionCheck(inside, h.getLevel(), MobSpawnType.SPAWNER, null);
        NeoForge.EVENT_BUS.post(event);
        h.assertTrue(
                event.getResult() == MobSpawnEvent.PositionCheck.Result.FAIL,
                "Spawner position check rejected");
        var reload = new EntityJoinLevelEvent(inside, h.getLevel(), true);
        NeoForge.EVENT_BUS.post(reload);
        h.assertTrue(
                !reload.isCanceled(),
                "Spawn prevention never cancels existing mobs loaded from disk");
        stopper.preventSpawns = false;
        h.assertTrue(
                h.getLevel().addFreshEntity(mob(h, EntityType.ZOMBIE, center)),
                "Spawn prevention can be disabled independently");
        h.getLevel().setBlockAndUpdate(stopper.getBlockPos(), Blocks.AIR.defaultBlockState());
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void removalAndPersistentMobs(GameTestHelper h) {
        var stopper = stopper(h);
        stopper.preventSpawns = false;
        Vec3 center = Vec3.atCenterOf(stopper.getBlockPos());
        var zombie = mob(h, EntityType.ZOMBIE, center);
        var named = mob(h, EntityType.ZOMBIE, center);
        named.setCustomName(Component.literal("Build friend"));
        var persistent = mob(h, EntityType.ZOMBIE, center);
        persistent.setPersistenceRequired();
        var cow = mob(h, EntityType.COW, center);
        var villager = mob(h, EntityType.VILLAGER, center);
        var outside = mob(h, EntityType.ZOMBIE, center.add(4.01, 0, 0));
        for (var mob : new Mob[] {zombie, named, persistent, cow, villager, outside})
            h.getLevel().addFreshEntity(mob);
        stopper.removeMonsters = false;
        CozyProtection.sweep(h.getLevel());
        h.assertTrue(!zombie.isRemoved(), "Removal can be disabled");
        stopper.removeMonsters = true;
        CozyProtection.sweep(h.getLevel());
        h.assertTrue(zombie.isRemoved(), "Existing hostile discarded");
        h.assertTrue(
                !named.isRemoved() && !persistent.isRemoved(),
                "Named and persistent mobs preserved by default");
        h.assertTrue(
                !cow.isRemoved() && !villager.isRemoved() && !outside.isRemoved(),
                "Passive mobs and outside hostiles preserved");
        h.assertTrue(
                h.getLevel()
                        .getEntitiesOfClass(
                                net.minecraft.world.entity.item.ItemEntity.class,
                                new net.minecraft.world.phys.AABB(stopper.getBlockPos()).inflate(4))
                        .isEmpty(),
                "Removal creates no loot");
        stopper.preservePersistent = false;
        CozyProtection.sweep(h.getLevel());
        h.assertTrue(
                named.isRemoved() && persistent.isRemoved(), "Named protection is configurable");
        h.getLevel().setBlockAndUpdate(stopper.getBlockPos(), Blocks.AIR.defaultBlockState());
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void lifecycleAndSavedSettings(GameTestHelper h) {
        var stopper = stopper(h);
        stopper.radius = 17;
        stopper.preventSpawns = false;
        stopper.removeMonsters = true;
        stopper.preservePersistent = false;
        stopper.owner = UUID.randomUUID();
        var saved = stopper.saveWithoutMetadata(h.getLevel().registryAccess());
        var copy = new MonsterStopperEntity(stopper.getBlockPos(), stopper.getBlockState());
        copy.loadWithComponents(saved, h.getLevel().registryAccess());
        h.assertTrue(
                copy.radius == 17
                        && !copy.preventSpawns
                        && copy.removeMonsters
                        && !copy.preservePersistent
                        && stopper.owner.equals(copy.owner),
                "All settings and owner survive save/load");
        saved.putInt("radius", Integer.MAX_VALUE);
        copy.loadWithComponents(saved, h.getLevel().registryAccess());
        h.assertTrue(copy.radius == MonsterStopperEntity.MAX_RADIUS, "Saved radius bounded");
        stopper.preventSpawns = true;
        var zombie = mob(h, EntityType.ZOMBIE, Vec3.atCenterOf(stopper.getBlockPos()));
        h.assertTrue(CozyProtection.blocksSpawn(h.getLevel(), zombie), "Loaded stopper indexed");
        stopper.onChunkUnloaded();
        h.assertTrue(
                !CozyProtection.blocksSpawn(h.getLevel(), zombie), "Unloaded stopper inactive");
        stopper.onLoad();
        h.assertTrue(CozyProtection.blocksSpawn(h.getLevel(), zombie), "Reloaded stopper active");
        h.getLevel().setBlockAndUpdate(stopper.getBlockPos(), Blocks.AIR.defaultBlockState());
        h.assertTrue(
                !CozyProtection.blocksSpawn(h.getLevel(), zombie),
                "Broken stopper removed from index");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void blockCommandsAndOwnership(GameTestHelper h) throws Exception {
        var stopper = stopper(h);
        var player = player(h);
        stopper.owner = player.getUUID();
        player.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(stopper.getBlockPos()));
        var dispatcher = h.getLevel().getServer().getCommands().getDispatcher();
        var source = player.createCommandSourceStack().withPermission(0);
        h.assertTrue(
                dispatcher.execute("monsterstopper range 12", source) == 1 && stopper.radius == 12,
                "Owner can configure without OP");
        h.assertTrue(
                dispatcher.execute("monsterstopper remove_monsters false", source) == 1
                        && !stopper.removeMonsters,
                "Owner can disable removal");
        stopper.owner = UUID.randomUUID();
        h.assertTrue(
                dispatcher.execute("monsterstopper range 20", source) == 0 && stopper.radius == 12,
                "Other players cannot configure");
        h.assertTrue(
                dispatcher.execute("monsterstopper", source) == 1, "Other players can inspect");
        boolean rejected = false;
        try {
            dispatcher.execute("monsterstopper range 129", source);
        } catch (CommandSyntaxException expected) {
            rejected = true;
        }
        h.assertTrue(rejected, "Out-of-bounds radius rejected");
        h.getLevel().setBlockAndUpdate(stopper.getBlockPos(), Blocks.AIR.defaultBlockState());
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void charmEquipmentAndConfiguration(GameTestHelper h) throws Exception {
        var player = player(h);
        var charm = new ItemStack(CozyProtection.CHARM.get());
        player.setItemSlot(EquipmentSlot.OFFHAND, charm);
        h.assertTrue(CozyProtection.protectedPlayer(player), "Offhand charm protects by default");
        var dispatcher = h.getLevel().getServer().getCommands().getDispatcher();
        var source = player.createCommandSourceStack().withPermission(0);
        h.assertTrue(
                dispatcher.execute("monstercharm range 13", source) == 1
                        && MonsterCharmItem.radius(charm) == 13,
                "Player sets removal range");
        dispatcher.execute("monstercharm remove_monsters true", source);
        dispatcher.execute("monstercharm preserve_persistent false", source);
        var copied =
                ItemStack.parse(
                                h.getLevel().registryAccess(),
                                charm.save(h.getLevel().registryAccess()))
                        .orElseThrow();
        h.assertTrue(
                MonsterCharmItem.radius(copied) == 13
                        && MonsterCharmItem.removeMonsters(copied)
                        && !MonsterCharmItem.preservePersistent(copied),
                "Item settings survive serialized save/load");
        dispatcher.execute("monstercharm prevent_attacks false", source);
        h.assertTrue(!CozyProtection.protectedPlayer(player), "Attack prevention can be disabled");
        player.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
        player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(CozyProtection.CHARM.get()));
        h.assertTrue(
                !CozyProtection.protectedPlayer(player), "Carrying in mainhand is not equipping");
        h.assertTrue(
                dispatcher.execute("monstercharm range 4", source) == 0,
                "Cannot configure unequipped charm");
        CozyProtection.CHARM.get().use(h.getLevel(), player, InteractionHand.MAIN_HAND);
        h.assertTrue(CozyProtection.protectedPlayer(player), "Right-click equips in offhand");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void charmStopsTargetsAndHostileDamage(GameTestHelper h) {
        var player = player(h);
        player.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(CozyProtection.CHARM.get()));
        var zombie = mob(h, EntityType.ZOMBIE, player.position().add(1, 0, 0));
        zombie.setTarget(player);
        h.assertTrue(zombie.getTarget() == null, "New hostile target blocked");
        player.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
        zombie.setTarget(player);
        h.assertTrue(
                zombie.getTarget() == player, "Unprotected player remains a valid hostile target");
        player.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(CozyProtection.CHARM.get()));
        NeoForge.EVENT_BUS.post(new EntityTickEvent.Pre(zombie));
        h.assertTrue(zombie.getTarget() == null, "Equipping clears existing target");
        var unprotected = player(h);
        zombie.setTarget(unprotected);
        h.assertTrue(
                zombie.getTarget() == unprotected, "Other players retain normal hostile targeting");
        var hoglin = mob(h, EntityType.HOGLIN, player.position().add(1, 0, 0));
        h.assertTrue(
                hoglin.getBrain()
                        .checkMemory(MemoryModuleType.ATTACK_TARGET, MemoryStatus.REGISTERED),
                "Hoglin uses brain attack memory");
        hoglin.getBrain().setMemory(MemoryModuleType.ATTACK_TARGET, player);
        NeoForge.EVENT_BUS.post(new EntityTickEvent.Pre(hoglin));
        h.assertTrue(
                !hoglin.getBrain().hasMemoryValue(MemoryModuleType.ATTACK_TARGET),
                "Existing brain attack memory cleared");
        h.assertTrue(
                incoming(player, h.getLevel().damageSources().mobAttack(zombie)).isCanceled(),
                "Hostile melee damage intercepted");
        var arrow = EntityType.ARROW.create(h.getLevel());
        arrow.setOwner(zombie);
        h.assertTrue(
                incoming(player, h.getLevel().damageSources().arrow(arrow, zombie)).isCanceled(),
                "Hostile projectile damage intercepted");
        var other = player(h);
        h.assertTrue(
                !incoming(player, h.getLevel().damageSources().playerAttack(other)).isCanceled(),
                "Player-sourced damage is unchanged");
        h.assertTrue(
                !incoming(player, h.getLevel().damageSources().fall()).isCanceled(),
                "Environmental damage is unchanged");
        player.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
        h.assertTrue(
                !incoming(player, h.getLevel().damageSources().mobAttack(zombie)).isCanceled(),
                "Unequipping restores hostile damage");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void charmRemovesOnlyNearbyHostiles(GameTestHelper h) {
        var player = player(h);
        var charm = new ItemStack(CozyProtection.CHARM.get());
        MonsterCharmItem.configure(charm, "radius", 3);
        MonsterCharmItem.configure(charm, "remove_monsters", 1);
        player.setItemSlot(EquipmentSlot.OFFHAND, charm);
        // Register this test player in the level so the real periodic sweep sees it.
        h.getLevel().addNewPlayer(player);
        try {
            var inside = mob(h, EntityType.ZOMBIE, player.position().add(2.9, 0, 0));
            var outside = mob(h, EntityType.ZOMBIE, player.position().add(3.1, 0, 0));
            var named = mob(h, EntityType.ZOMBIE, player.position());
            named.setCustomName(Component.literal("Keep"));
            var cow = mob(h, EntityType.COW, player.position());
            for (var mob : new Mob[] {inside, outside, named, cow})
                h.getLevel().addFreshEntity(mob);
            CozyProtection.sweep(h.getLevel());
            h.assertTrue(
                    inside.isRemoved()
                            && !outside.isRemoved()
                            && !named.isRemoved()
                            && !cow.isRemoved(),
                    "Moving aura removes only eligible hostiles inside sphere");
            player.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
            var replacement = mob(h, EntityType.ZOMBIE, player.position());
            h.getLevel().addFreshEntity(replacement);
            CozyProtection.sweep(h.getLevel());
            h.assertTrue(!replacement.isRemoved(), "Unequipping stops removal");
        } finally {
            player.discard();
        }
        h.succeed();
    }
}
