// SPDX-License-Identifier: MPL-2.0
package net.noopsnet.core;

import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.MobSpawnEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@GameTestHolder("noopsnet_core")
@PrefixGameTestTemplate(false)
public final class MiningGameTests {
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void flatMiningTerrainHasRealOresAndPermanentDaylight(GameTestHelper h) {
        var level = h.getLevel().getServer().getLevel(MiningDimension.KEY);
        h.assertTrue(level != null, "Mining dimension registered from bundled worldgen data");
        h.assertTrue(level.dimensionType().hasFixedTime(), "Mining has permanent daytime");
        h.assertTrue(Math.abs(level.getTimeOfDay(0)) < .001, "Fixed time is noon");
        Map<String, Integer> ores = new HashMap<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int cx = 4; cx < 6; cx++)
            for (int cz = 4; cz < 6; cz++) {
                var chunk = level.getChunk(cx, cz);
                for (int x = cx * 16; x < cx * 16 + 16; x++)
                    for (int z = cz * 16; z < cz * 16 + 16; z++) {
                        h.assertTrue(
                                level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z)
                                        == 65,
                                "Flat surface stays at Y64");
                        h.assertTrue(
                                level.getBlockState(pos.set(x, -64, z)).is(Blocks.BEDROCK),
                                "Bedrock floor intact");
                        for (int y = -63; y <= 60; y++) {
                            String name =
                                    BuiltInRegistries.BLOCK
                                            .getKey(
                                                    chunk.getBlockState(pos.set(x, y, z))
                                                            .getBlock())
                                            .getPath();
                            if (name.endsWith("_ore")
                                    || name.startsWith("ore_")
                                    || name.startsWith("deepslate_ore_"))
                                ores.merge(name, 1, Integer::sum);
                        }
                    }
            }
        int total = ores.values().stream().mapToInt(Integer::intValue).sum();
        h.assertTrue(
                total > 500, "Boosted ore features actually generated in flat terrain: " + ores);
        h.assertTrue(
                ores.keySet().stream().anyMatch(s -> s.contains("diamond")),
                "Diamond ore generated");
        h.assertTrue(
                ores.keySet().stream().anyMatch(s -> s.contains("iron")), "Iron ore generated");
        h.assertTrue(
                level.getBiome(new BlockPos(80, 65, 80))
                        .value()
                        .getMobSettings()
                        .getMobs(MobCategory.MONSTER)
                        .isEmpty(),
                "Mining biome has no natural hostile spawns");
        if (net.neoforged.fml.ModList.get().isLoaded("immersiveengineering"))
            h.assertTrue(
                    ores.keySet().stream()
                            .anyMatch(s -> s.contains("ore_aluminum") || s.contains("ore_lead")),
                    "Optional Immersive Engineering ores actually generate");
        LogUtils.getLogger().info("Mining dimension native ore sample (four chunks): {}", ores);
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void miningRejectsHostilesWithoutChangingOverworldPve(GameTestHelper h) {
        var level = h.getLevel().getServer().getLevel(MiningDimension.KEY);
        h.assertTrue(level != null, "Mining dimension available");
        boolean spawning = h.getLevel().getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING);
        var zombie = EntityType.ZOMBIE.create(level);
        zombie.setPos(.5, 65, .5);
        zombie.setCustomName(Component.literal("Even named hostiles are disabled here"));
        zombie.setPersistenceRequired();
        h.assertTrue(!level.addFreshEntity(zombie), "Fresh hostiles rejected in mining");
        var check = new MobSpawnEvent.PositionCheck(zombie, level, MobSpawnType.SPAWNER, null);
        NeoForge.EVENT_BUS.post(check);
        h.assertTrue(
                check.getResult() == MobSpawnEvent.PositionCheck.Result.FAIL,
                "Spawner checks fail in mining");
        var disk = new EntityJoinLevelEvent(zombie, level, true);
        NeoForge.EVENT_BUS.post(disk);
        h.assertTrue(!disk.isCanceled(), "Disk loads remain safe; cleanup is deferred until tick");
        var tick = new EntityTickEvent.Pre(zombie);
        NeoForge.EVENT_BUS.post(tick);
        h.assertTrue(
                zombie.isRemoved() && tick.isCanceled(),
                "Existing hostiles discarded before AI can run");
        var cow = EntityType.COW.create(level);
        cow.setPos(.5, 65, .5);
        h.assertTrue(level.addFreshEntity(cow), "Non-hostile mobs are unaffected");
        cow.discard();
        var overworld = EntityType.ZOMBIE.create(h.getLevel());
        overworld.setPos(h.absolutePos(new BlockPos(2, 2, 2)).getCenter());
        h.assertTrue(h.getLevel().addFreshEntity(overworld), "Overworld PvE remains enabled");
        overworld.discard();
        h.assertTrue(
                spawning == h.getLevel().getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING),
                "Global mob spawning gamerule unchanged");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void nonOperatorMiningTravelReturnsWithoutReplacingHome(GameTestHelper h)
            throws Exception {
        var level = h.getLevel();
        var player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "MiningTravel"));
        var network =
                new io.netty.channel.embedded.EmbeddedChannel(player.connection.getConnection());
        var floor = h.absolutePos(new BlockPos(2, 1, 2));
        level.setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(floor.above(), Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(floor.above(2), Blocks.AIR.defaultBlockState());
        player.setPos(floor.getX() + .5, floor.getY() + 1, floor.getZ() + .5);
        var origin = player.position();
        var dispatcher = level.getServer().getCommands().getDispatcher();
        var source = player.createCommandSourceStack().withPermission(0);
        h.assertTrue(dispatcher.execute("sethome keep", source) == 1, "Independent home saved");
        h.assertTrue(dispatcher.execute("mining", source) == 1, "Non-OP enters mining");
        h.assertTrue(MiningDimension.isMining(player.level()), "Player reached mining");
        h.assertTrue(dispatcher.execute("mining return", source) == 0, "Mining cooldown enforced");
        h.runAfterDelay(
                220,
                () -> {
                    try {
                        h.assertTrue(
                                dispatcher.execute("mining return", source) == 1,
                                "Non-OP returns after cooldown");
                        h.assertTrue(
                                player.level() == level
                                        && player.position().distanceToSqr(origin) < .001,
                                "Return restores original position and dimension");
                        h.assertTrue(
                                dispatcher.execute("home keep", source) == 1,
                                "Mining travel leaves existing home intact");
                        player.discard();
                        network.finishAndReleaseAll();
                        h.succeed();
                    } catch (Exception error) {
                        throw new RuntimeException(error);
                    }
                });
    }
}
