// SPDX-License-Identifier: MPL-2.0
package net.noopsnet.core;

import com.mojang.logging.LogUtils;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Independent, durable return locations; entering mining never replaces a player's home. */
public final class MiningDimension {
    public static final ResourceKey<Level> KEY =
            ResourceKey.create(
                    Registries.DIMENSION,
                    ResourceLocation.fromNamespaceAndPath("noopsnet_core", "mining"));
    private static HomeStore returns;
    private static final Map<UUID, Long> COOLDOWNS = new HashMap<>();

    private MiningDimension() {}

    static void register() {
        NeoForge.EVENT_BUS.addListener(
                (ServerAboutToStartEvent event) -> {
                    returns = null;
                    COOLDOWNS.clear();
                    try {
                        returns =
                                new HomeStore(
                                        event.getServer()
                                                .getWorldPath(LevelResource.ROOT)
                                                .resolve("data/noopsnet_core/mining-returns.json"));
                    } catch (IOException error) {
                        LogUtils.getLogger()
                                .error("Mining travel disabled; return data preserved", error);
                    }
                });
        NeoForge.EVENT_BUS.addListener(
                (ServerStoppedEvent event) -> {
                    returns = null;
                    COOLDOWNS.clear();
                });
        NeoForge.EVENT_BUS.addListener(
                (RegisterCommandsEvent event) ->
                        event.getDispatcher()
                                .register(
                                        Commands.literal("mining")
                                                .requires(
                                                        source ->
                                                                source.getEntity()
                                                                        instanceof ServerPlayer)
                                                .executes(
                                                        context ->
                                                                travel(context.getSource(), false))
                                                .then(
                                                        Commands.literal("return")
                                                                .executes(
                                                                        context ->
                                                                                travel(
                                                                                        context
                                                                                                .getSource(),
                                                                                        true)))));
    }

    public static boolean isMining(Level level) {
        return level.dimension().equals(KEY);
    }

    private static int fail(CommandSourceStack source, String message) {
        source.sendFailure(Component.literal(message));
        return 0;
    }

    private static int travel(CommandSourceStack source, boolean returning) {
        ServerPlayer player = (ServerPlayer) source.getEntity();
        if (returns == null)
            return fail(source, "Mining travel is unavailable. Please contact staff.");
        if (!player.isAlive() || player.isSleeping() || player.isPassenger())
            return fail(source, "Wake up or dismount before mining travel.");
        if (returning != isMining(player.level()))
            return fail(
                    source,
                    returning
                            ? "You are not in the mining dimension."
                            : "You are already in the mining dimension. Use /mining return to"
                                    + " leave.");
        long now = source.getServer().overworld().getGameTime();
        long remaining = COOLDOWNS.getOrDefault(player.getUUID(), now) - now;
        if (remaining > 0)
            return fail(source, "Wait " + (remaining + 19) / 20 + " seconds before mining travel.");
        ServerLevel target;
        Vec3 position = null;
        float yaw = player.getYRot(), pitch = player.getXRot();
        if (returning) {
            var saved = returns.get(player.getUUID());
            if (saved == null)
                return fail(source, "No mining return location is saved. Use /spawn to leave.");
            target =
                    source.getServer()
                            .getLevel(
                                    ResourceKey.create(
                                            Registries.DIMENSION,
                                            ResourceLocation.parse(saved.dimension())));
            if (target == null)
                return fail(
                        source,
                        "Your return dimension is unavailable. Your saved return has been kept; use"
                                + " /spawn to leave.");
            position = new Vec3(saved.x(), saved.y(), saved.z());
            yaw = saved.yaw();
            pitch = saved.pitch();
            if (!NoopsCore.safe(player, target, position))
                return fail(
                        source,
                        "Your return position is unsafe. Your saved return has been kept; use"
                                + " /spawn to leave.");
        } else {
            target = source.getServer().getLevel(KEY);
            if (target == null) return fail(source, "The mining dimension is unavailable.");
            // Generate only the chunks needed by normal travel; no pre-generation job or block
            // paste.
            for (int radius = 0; radius <= 8 && position == null; radius++) {
                for (int x = -radius; x <= radius && position == null; x++)
                    for (int z = -radius; z <= radius; z++) {
                        if (Math.max(Math.abs(x), Math.abs(z)) != radius) continue;
                        int height =
                                target.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                        Vec3 candidate = new Vec3(x + .5, height, z + .5);
                        if (NoopsCore.safe(player, target, candidate)) {
                            position = candidate;
                            break;
                        }
                    }
            }
            if (position == null)
                return fail(
                        source,
                        "The mining arrival area is obstructed. No blocks have been changed.");
            try {
                returns.set(
                        player.getUUID(),
                        new HomeStore.Home(
                                "mining",
                                player.level().dimension().location().toString(),
                                player.getX(),
                                player.getY(),
                                player.getZ(),
                                yaw,
                                pitch));
            } catch (IOException error) {
                LogUtils.getLogger().error("Could not save mining return location", error);
                return fail(
                        source,
                        "Could not save your return location; you have not been teleported.");
            }
        }
        player.teleportTo(
                target, position.x, position.y, position.z, java.util.Set.of(), yaw, pitch);
        player.setDeltaMovement(Vec3.ZERO);
        player.fallDistance = 0;
        COOLDOWNS.put(player.getUUID(), now + 200);
        source.sendSuccess(
                () ->
                        Component.literal(
                                returning
                                        ? "Returned from mining."
                                        : "Welcome to mining: flat terrain, boosted ores and no"
                                                + " hostile mobs. Use /mining return to leave."),
                false);
        return 1;
    }
}
