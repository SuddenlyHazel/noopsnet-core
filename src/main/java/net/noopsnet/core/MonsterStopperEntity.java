// SPDX-License-Identifier: MPL-2.0
package net.noopsnet.core;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

public final class MonsterStopperEntity extends BlockEntity {
    public static final int MAX_RADIUS = 128;
    UUID owner;
    volatile int radius = 32;
    volatile boolean preventSpawns = true, removeMonsters = true, preservePersistent = true;

    public MonsterStopperEntity(BlockPos pos, BlockState state) {
        super(CozyProtection.STOPPER_ENTITY.get(), pos, state);
    }

    boolean canConfigure(ServerPlayer player) {
        return player.level() == level
                && player.isAlive()
                && !player.isSpectator()
                && player.distanceToSqr(Vec3.atCenterOf(worldPosition)) <= 36
                && level.mayInteract(player, worldPosition)
                && (player.hasPermissions(2) || player.getUUID().equals(owner));
    }

    boolean contains(Vec3 point) {
        return Vec3.atCenterOf(worldPosition).distanceToSqr(point) <= (double) radius * radius;
    }

    @Override
    public void onLoad() {
        super.onLoad();
        CozyProtection.loaded(this);
    }

    @Override
    public void setRemoved() {
        CozyProtection.unloaded(this);
        super.setRemoved();
    }

    @Override
    public void onChunkUnloaded() {
        CozyProtection.unloaded(this);
        super.onChunkUnloaded();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (owner != null) tag.putUUID("owner", owner);
        tag.putInt("radius", radius);
        tag.putBoolean("prevent_spawns", preventSpawns);
        tag.putBoolean("remove_monsters", removeMonsters);
        tag.putBoolean("preserve_persistent", preservePersistent);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        owner = tag.hasUUID("owner") ? tag.getUUID("owner") : null;
        radius = tag.contains("radius") ? Math.clamp(tag.getInt("radius"), 1, MAX_RADIUS) : 32;
        preventSpawns = !tag.contains("prevent_spawns") || tag.getBoolean("prevent_spawns");
        removeMonsters = !tag.contains("remove_monsters") || tag.getBoolean("remove_monsters");
        preservePersistent =
                !tag.contains("preserve_persistent") || tag.getBoolean("preserve_persistent");
    }
}
