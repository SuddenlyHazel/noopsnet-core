// SPDX-License-Identifier: MPL-2.0
package net.noopsnet.core;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.MobSpawnEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Server-side protection; the index contains only currently loaded block entities. */
public final class CozyProtection {
    private static final String ID = "noopsnet_core";
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ID);
    private static final DeferredRegister<BlockEntityType<?>> ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, ID);
    public static final DeferredBlock<MonsterStopperBlock> STOPPER =
            BLOCKS.register(
                    "monster_stopper",
                    () ->
                            new MonsterStopperBlock(
                                    BlockBehaviour.Properties.of()
                                            .strength(3.5F)
                                            .requiresCorrectToolForDrops()));
    public static final DeferredItem<BlockItem> STOPPER_ITEM =
            ITEMS.registerSimpleBlockItem(STOPPER);
    public static final DeferredItem<MonsterCharmItem> CHARM =
            ITEMS.register(
                    "monster_charm", () -> new MonsterCharmItem(new Item.Properties().stacksTo(1)));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MonsterStopperEntity>>
            STOPPER_ENTITY =
                    ENTITIES.register(
                            "monster_stopper",
                            () ->
                                    BlockEntityType.Builder.of(
                                                    MonsterStopperEntity::new, STOPPER.get())
                                            .build(null));
    private static final Map<ServerLevel, Map<BlockPos, MonsterStopperEntity>> LOADED =
            new ConcurrentHashMap<>();

    private CozyProtection() {}

    static void register(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
        ENTITIES.register(bus);
        bus.addListener(
                (BuildCreativeModeTabContentsEvent event) -> {
                    if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
                        event.accept(STOPPER_ITEM);
                        event.accept(CHARM);
                    }
                });
        NeoForge.EVENT_BUS.addListener(CozyCommands::register);
        NeoForge.EVENT_BUS.addListener(CozyProtection::positionCheck);
        NeoForge.EVENT_BUS.addListener(CozyProtection::join);
        NeoForge.EVENT_BUS.addListener(CozyProtection::tick);
        NeoForge.EVENT_BUS.addListener(CozyProtection::target);
        NeoForge.EVENT_BUS.addListener(CozyProtection::damage);
        NeoForge.EVENT_BUS.addListener(CozyProtection::entityTick);
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> LOADED.clear());
    }

    static void loaded(MonsterStopperEntity stopper) {
        if (stopper.getLevel() instanceof ServerLevel level)
            LOADED.computeIfAbsent(level, ignored -> new ConcurrentHashMap<>())
                    .put(stopper.getBlockPos(), stopper);
    }

    static void unloaded(MonsterStopperEntity stopper) {
        if (stopper.getLevel() instanceof ServerLevel level) {
            var entries = LOADED.get(level);
            if (entries != null) {
                entries.remove(stopper.getBlockPos(), stopper);
                if (entries.isEmpty()) LOADED.remove(level);
            }
        }
    }

    static boolean hostile(Entity entity) {
        return entity instanceof Mob
                && (entity instanceof Enemy
                        || entity.getType().getCategory() == MobCategory.MONSTER);
    }

    static boolean removable(Mob mob, boolean preservePersistent) {
        return hostile(mob)
                && (!preservePersistent
                        || (!mob.hasCustomName()
                                && !mob.isPersistenceRequired()
                                && !mob.requiresCustomPersistence()));
    }

    static boolean blocksSpawn(ServerLevel level, Mob mob) {
        var entries = LOADED.get(level);
        if (entries == null || !hostile(mob)) return false;
        for (var stopper : entries.values())
            if (!stopper.isRemoved()
                    && stopper.preventSpawns
                    && stopper.contains(mob.position())
                    && removable(mob, stopper.preservePersistent)) return true;
        return false;
    }

    private static void positionCheck(MobSpawnEvent.PositionCheck event) {
        if (blocksSpawn(event.getLevel().getLevel(), event.getEntity()))
            event.setResult(MobSpawnEvent.PositionCheck.Result.FAIL);
    }

    private static void join(EntityJoinLevelEvent event) {
        // Never cancel disk loads: remove existing mobs only through the explicit sweep setting.
        // Reading the index here does not access or load any chunks.
        if (!event.loadedFromDisk()
                && event.getLevel() instanceof ServerLevel level
                && event.getEntity() instanceof Mob mob
                && blocksSpawn(level, mob)) event.setCanceled(true);
    }

    static ItemStack equipped(ServerPlayer player) {
        ItemStack stack = player.getOffhandItem();
        return player.isAlive() && !player.isSpectator() && stack.is(CHARM.get())
                ? stack
                : ItemStack.EMPTY;
    }

    static boolean protectedPlayer(Entity entity) {
        if (!(entity instanceof ServerPlayer player)) return false;
        ItemStack stack = equipped(player);
        return !stack.isEmpty() && MonsterCharmItem.preventAttacks(stack);
    }

    private static void target(LivingChangeTargetEvent event) {
        if (hostile(event.getEntity()) && protectedPlayer(event.getNewAboutToBeSetTarget()))
            event.setNewAboutToBeSetTarget(null);
    }

    private static void damage(LivingIncomingDamageEvent event) {
        if (protectedPlayer(event.getEntity())
                && (hostile(event.getSource().getEntity())
                        || hostile(event.getSource().getDirectEntity()))) event.setCanceled(true);
    }

    private static void entityTick(EntityTickEvent.Pre event) {
        if (event.getEntity() instanceof Mob mob && !mob.level().isClientSide && hostile(mob)) {
            if (protectedPlayer(mob.getTarget())) mob.setTarget(null);
            if (mob.getBrain()
                    .checkMemory(MemoryModuleType.ATTACK_TARGET, MemoryStatus.REGISTERED)) {
                var target = mob.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET);
                if (target.isPresent() && protectedPlayer(target.get()))
                    mob.getBrain().eraseMemory(MemoryModuleType.ATTACK_TARGET);
            }
        }
    }

    private static void tick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.getGameTime() % 20 != 0)
            return;
        sweep(level);
    }

    static void sweep(ServerLevel level) {
        var entries = LOADED.get(level);
        if (entries != null)
            for (var stopper : entries.values()) {
                if (stopper.isRemoved() || !stopper.removeMonsters) continue;
                for (Mob mob :
                        level.getEntitiesOfClass(
                                Mob.class,
                                new AABB(stopper.getBlockPos()).inflate(stopper.radius),
                                m -> removable(m, stopper.preservePersistent)))
                    if (stopper.contains(mob.position())) mob.discard();
            }
        for (ServerPlayer player : level.players()) {
            ItemStack charm = equipped(player);
            if (charm.isEmpty() || !MonsterCharmItem.removeMonsters(charm)) continue;
            int radius = MonsterCharmItem.radius(charm);
            for (Mob mob :
                    level.getEntitiesOfClass(
                            Mob.class,
                            player.getBoundingBox().inflate(radius),
                            m -> removable(m, MonsterCharmItem.preservePersistent(charm))))
                if (player.distanceToSqr(mob) <= (double) radius * radius) mob.discard();
        }
    }
}
