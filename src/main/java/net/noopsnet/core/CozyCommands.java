// SPDX-License-Identifier: MPL-2.0
package net.noopsnet.core;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

public final class CozyCommands {
    private CozyCommands() {}

    static void register(RegisterCommandsEvent event) {
        for (boolean charm : new boolean[] {false, true}) {
            String root = charm ? "monstercharm" : "monsterstopper";
            LiteralArgumentBuilder<CommandSourceStack> command =
                    Commands.literal(root)
                            .requires(source -> source.getEntity() instanceof ServerPlayer)
                            .executes(c -> configure(c.getSource(), charm, "", 0));
            command.then(
                    Commands.literal("range")
                            .then(
                                    Commands.argument(
                                                    "blocks",
                                                    IntegerArgumentType.integer(
                                                            1,
                                                            charm
                                                                    ? MonsterCharmItem.MAX_RADIUS
                                                                    : MonsterStopperEntity
                                                                            .MAX_RADIUS))
                                            .executes(
                                                    c ->
                                                            configure(
                                                                    c.getSource(),
                                                                    charm,
                                                                    "radius",
                                                                    IntegerArgumentType.getInteger(
                                                                            c, "blocks")))));
            for (String setting :
                    new String[] {
                        charm ? "prevent_attacks" : "prevent_spawns",
                        "remove_monsters",
                        "preserve_persistent"
                    })
                command.then(
                        Commands.literal(setting)
                                .then(
                                        Commands.argument("enabled", BoolArgumentType.bool())
                                                .executes(
                                                        c ->
                                                                configure(
                                                                        c.getSource(),
                                                                        charm,
                                                                        setting,
                                                                        BoolArgumentType.getBool(
                                                                                        c,
                                                                                        "enabled")
                                                                                ? 1
                                                                                : 0))));
            event.getDispatcher().register(command);
        }
    }

    private static int configure(CommandSourceStack source, boolean charm, String key, int value) {
        ServerPlayer player = (ServerPlayer) source.getEntity();
        if (charm) {
            ItemStack stack = CozyProtection.equipped(player);
            if (stack.isEmpty()) return fail(source, "Equip a MonsterCharm in your offhand first.");
            if (!key.isEmpty()) MonsterCharmItem.configure(stack, key, value);
            showCharm(player, stack);
            return 1;
        }
        if (!(player.pick(6, 1, false) instanceof BlockHitResult hit)
                || !player.serverLevel()
                        .hasChunk(hit.getBlockPos().getX() >> 4, hit.getBlockPos().getZ() >> 4)
                || !(player.serverLevel().getBlockEntity(hit.getBlockPos())
                        instanceof MonsterStopperEntity stopper))
            return fail(source, "Look at a MonsterStopper within 6 blocks.");
        if (!key.isEmpty()) {
            if (!stopper.canConfigure(player))
                return fail(
                        source,
                        "Only the placing player or an operator can configure this"
                                + " MonsterStopper.");
            switch (key) {
                case "radius" -> stopper.radius = value;
                case "prevent_spawns" -> stopper.preventSpawns = value != 0;
                case "remove_monsters" -> stopper.removeMonsters = value != 0;
                case "preserve_persistent" -> stopper.preservePersistent = value != 0;
                default -> throw new IllegalArgumentException(key);
            }
            stopper.setChanged();
        }
        showBlock(player, stopper);
        return 1;
    }

    private static int fail(CommandSourceStack source, String text) {
        source.sendFailure(Component.literal(text));
        return 0;
    }

    static void showBlock(ServerPlayer player, MonsterStopperEntity stopper) {
        player.sendSystemMessage(
                Component.literal(
                                "MonsterStopper • radius "
                                        + stopper.radius
                                        + " blocks • loaded chunks only")
                        .withStyle(ChatFormatting.AQUA));
        player.sendSystemMessage(
                Component.literal(
                        "Stop spawns: "
                                + stopper.preventSpawns
                                + " • Remove hostiles: "
                                + stopper.removeMonsters
                                + " • Preserve named/persistent: "
                                + stopper.preservePersistent));
        if (stopper.canConfigure(player))
            controls(
                    player,
                    "monsterstopper",
                    stopper.radius,
                    "prevent_spawns",
                    stopper.preventSpawns,
                    stopper.removeMonsters,
                    stopper.preservePersistent);
    }

    static void showCharm(ServerPlayer player, ItemStack charm) {
        player.sendSystemMessage(
                Component.literal(
                                "MonsterCharm • equipped in offhand • removal radius "
                                        + MonsterCharmItem.radius(charm)
                                        + " blocks")
                        .withStyle(ChatFormatting.AQUA));
        player.sendSystemMessage(
                Component.literal(
                        "Prevent hostile attacks: "
                                + MonsterCharmItem.preventAttacks(charm)
                                + " • Remove hostiles: "
                                + MonsterCharmItem.removeMonsters(charm)
                                + " • Preserve named/persistent: "
                                + MonsterCharmItem.preservePersistent(charm)));
        player.sendSystemMessage(
                Component.literal("Keep your inventory, armor and charm on death while equipped."));
        controls(
                player,
                "monstercharm",
                MonsterCharmItem.radius(charm),
                "prevent_attacks",
                MonsterCharmItem.preventAttacks(charm),
                MonsterCharmItem.removeMonsters(charm),
                MonsterCharmItem.preservePersistent(charm));
    }

    private static void controls(
            ServerPlayer player,
            String command,
            int radius,
            String prevention,
            boolean prevent,
            boolean remove,
            boolean preserve) {
        player.sendSystemMessage(
                Component.literal("[Set range] ")
                        .withStyle(
                                s ->
                                        s.withColor(ChatFormatting.GREEN)
                                                .withClickEvent(
                                                        new ClickEvent(
                                                                ClickEvent.Action.SUGGEST_COMMAND,
                                                                "/" + command + " range "
                                                                        + radius)))
                        .append(link("[Toggle prevention] ", command, prevention, !prevent))
                        .append(link("[Toggle removal] ", command, "remove_monsters", !remove))
                        .append(
                                link(
                                        "[Toggle named protection]",
                                        command,
                                        "preserve_persistent",
                                        !preserve)));
    }

    private static Component link(String label, String command, String setting, boolean value) {
        return Component.literal(label)
                .withStyle(
                        s ->
                                s.withColor(ChatFormatting.GREEN)
                                        .withClickEvent(
                                                new ClickEvent(
                                                        ClickEvent.Action.SUGGEST_COMMAND,
                                                        "/" + command + " " + setting + " "
                                                                + value)));
    }
}
