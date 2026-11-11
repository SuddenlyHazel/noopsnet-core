// SPDX-License-Identifier: MPL-2.0
package net.noopsnet.core;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.util.*;
import net.minecraft.commands.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.*;
import net.minecraft.server.level.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.*;

/** Common entrypoint on BOTH physical sides; commands remain server-authoritative. */
@Mod("noopsnet_core")
public final class NoopsCore {
    private HomeStore homes;
    private final Map<UUID, Long> homeCooldown = new HashMap<>(), spawnCooldown = new HashMap<>();
    public NoopsCore() {
        NeoForge.EVENT_BUS.addListener(this::starting);
        NeoForge.EVENT_BUS.addListener(this::stopped);
        NeoForge.EVENT_BUS.addListener(this::commands);
    }
    private void starting(ServerAboutToStartEvent event) {
        homeCooldown.clear(); spawnCooldown.clear(); homes = null;
        try { homes = new HomeStore(event.getServer().getWorldPath(LevelResource.ROOT).resolve("data/noopsnet_core/homes.json")); }
        catch (IOException ex) { LogUtils.getLogger().error("NoopsNet homes unavailable; existing data preserved", ex); }
    }
    private void stopped(ServerStoppedEvent event) { homes = null; homeCooldown.clear(); spawnCooldown.clear(); }
    private void commands(RegisterCommandsEvent event) {
        for (String command : List.of("sethome", "home", "delhome")) {
            event.getDispatcher().register(Commands.literal(command)
                .requires(s -> s.getEntity() instanceof ServerPlayer)
                .executes(c -> home(c.getSource(), command, null))
                .then(Commands.argument("name", StringArgumentType.word())
                    .suggests((c,b) -> {
                        if (homes != null && c.getSource().getEntity() instanceof ServerPlayer p) {
                            var h = homes.get(p.getUUID()); if (h != null) b.suggest(h.name());
                        }
                        return b.buildFuture();
                    }).executes(c -> home(c.getSource(), command, StringArgumentType.getString(c,"name")))));
        }
        event.getDispatcher().register(Commands.literal("spawn")
            .requires(s -> s.getEntity() instanceof ServerPlayer).executes(c -> spawn(c.getSource())));
    }
    private int fail(CommandSourceStack source, String message) { source.sendFailure(Component.literal(message)); return 0; }
    private int home(CommandSourceStack source, String action, String name) {
        ServerPlayer player = (ServerPlayer)source.getEntity();
        if (homes == null) return fail(source,"Homes are unavailable. Please contact staff.");
        var current = homes.get(player.getUUID());
        if (name != null && !name.matches("[a-zA-Z0-9_-]{1,32}")) return fail(source,"Use 1–32 letters, numbers, underscores or hyphens for a home name.");
        try {
            if (action.equals("sethome")) {
                if (player.isSpectator() || !player.isAlive()) return fail(source,"You cannot set a home right now.");
                var saved = new HomeStore.Home(name == null ? "home" : name, player.level().dimension().location().toString(),
                    player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
                homes.set(player.getUUID(), saved);
                source.sendSuccess(() -> Component.literal("Home '" + saved.name() + "' saved. Your previous home was replaced."),false);
                return 1;
            }
            if (current == null || name != null && !name.equals(current.name())) return fail(source,"No matching home. Use /sethome to save one.");
            if (action.equals("delhome")) {
                homes.set(player.getUUID(),null);
                source.sendSuccess(() -> Component.literal("Home deleted."),false); return 1;
            }
            ServerLevel level = source.getServer().getLevel(ResourceKey.create(Registries.DIMENSION,ResourceLocation.parse(current.dimension())));
            if (level == null) return fail(source,"Your home's dimension is unavailable. Your home has been kept.");
            return travel(source, level, new Vec3(current.x(),current.y(),current.z()),current.yaw(),current.pitch(),homeCooldown);
        } catch (IOException ex) {
            LogUtils.getLogger().error("Could not save NoopsNet home data",ex);
            return fail(source,"Could not save your home change. Please contact staff.");
        }
    }
    private int spawn(CommandSourceStack source) {
        ServerLevel level = source.getServer().overworld();
        BlockPos pos = level.getSharedSpawnPos();
        // Search close to the configured world spawn; never silently send players far away.
        for (int dy : new int[]{0,1,2,3,-1,-2,-3}) {
            Vec3 target = Vec3.atBottomCenterOf(pos.offset(0,dy,0));
            if (safe((ServerPlayer)source.getEntity(),level,target))
                return travel(source,level,target,level.getSharedSpawnAngle(),0,spawnCooldown);
        }
        return fail(source,"Spawn is obstructed or unsafe. Please contact staff.");
    }
    private boolean safe(ServerPlayer player, ServerLevel level, Vec3 target) {
        BlockPos pos = BlockPos.containing(target);
        if (level.isOutsideBuildHeight(pos) || level.isOutsideBuildHeight(pos.above()) || !level.getWorldBorder().isWithinBounds(pos)) return false;
        level.getChunk(pos.getX() >> 4,pos.getZ() >> 4);
        var box = player.getBoundingBox().move(target.subtract(player.position()));
        return level.noCollision(player,box) && !level.containsAnyLiquid(box)
            && level.getBlockState(pos.below()).isFaceSturdy(level,pos.below(),net.minecraft.core.Direction.UP)
            && !level.getBlockState(pos.below()).is(net.minecraft.world.level.block.Blocks.MAGMA_BLOCK)
            && !level.getBlockState(pos.below()).is(net.minecraft.world.level.block.Blocks.CAMPFIRE)
            && !level.getBlockState(pos.below()).is(net.minecraft.world.level.block.Blocks.SOUL_CAMPFIRE)
            && !level.getBlockState(pos).is(net.minecraft.world.level.block.Blocks.FIRE)
            && !level.getBlockState(pos).is(net.minecraft.world.level.block.Blocks.SOUL_FIRE);
    }
    private int travel(CommandSourceStack source, ServerLevel level, Vec3 target, float yaw, float pitch, Map<UUID,Long> cooldown) {
        ServerPlayer player = (ServerPlayer)source.getEntity();
        if (!player.isAlive() || player.isSleeping() || player.isPassenger()) return fail(source,"Wake up or dismount before teleporting.");
        long now = System.nanoTime(), remaining = cooldown.getOrDefault(player.getUUID(),now) - now;
        if (remaining > 0) return fail(source,"Wait " + (remaining + 999_999_999L)/1_000_000_000L + " seconds before using this teleport again.");
        if (!safe(player,level,target)) return fail(source,"That destination is obstructed or unsafe. Your home has been kept.");
        player.teleportTo(level,target.x,target.y,target.z,Set.of(),yaw,pitch);
        player.setDeltaMovement(Vec3.ZERO); player.fallDistance = 0;
        cooldown.put(player.getUUID(),now + 10_000_000_000L);
        source.sendSuccess(() -> Component.literal("Teleported."),false); return 1;
    }
}
