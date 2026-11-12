// SPDX-License-Identifier: MPL-2.0
package net.noopsnet.core.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.noopsnet.core.EmoteKeyPolicy;

import java.io.IOException;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;

/** Capture existing choices during mod construction, before newly registered keys are saved. */
@Mod(value = "noopsnet_core", dist = Dist.CLIENT)
public final class CoreClientDefaults {
    private final boolean configured;
    private boolean done;

    public CoreClientDefaults() {
        boolean existing = true;
        var options = FMLPaths.GAMEDIR.get().resolve("options.txt");
        try {
            existing =
                    Files.exists(options)
                            && EmoteKeyPolicy.alreadyConfigured(Files.readString(options));
        } catch (IOException error) {
            LogUtils.getLogger()
                    .warn(
                            "Could not inspect emote key preference; leaving controls unchanged",
                            error);
        }
        configured = existing;
        NeoForge.EVENT_BUS.addListener(this::tick);
    }

    private void tick(ClientTickEvent.Post event) {
        if (done) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options == null) return;
        done = true;
        KeyMapping wheel = null;
        Set<String> occupied = new HashSet<>();
        for (KeyMapping mapping : minecraft.options.keyMappings) {
            if (mapping.getName().equals(EmoteKeyPolicy.WHEEL)) wheel = mapping;
            else occupied.add(mapping.getKey().getName());
        }
        if (wheel != null
                && EmoteKeyPolicy.shouldAssign(configured, wheel.getKey().getName(), occupied)) {
            wheel.setKey(InputConstants.getKey(EmoteKeyPolicy.PREFERRED));
            KeyMapping.resetMapping();
            minecraft.options.save();
            LogUtils.getLogger().info("New Emotecraft wheel assigned to I; backpack remains on B");
        }
    }
}
