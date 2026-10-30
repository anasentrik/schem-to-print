package dev.schemprinter;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;

import java.util.Locale;

final class SchemCommand {
    private static final SuggestionProvider<CommandSourceStack> NAMES =
            (ctx, builder) -> SharedSuggestionProvider.suggest(Manager.listNames(), builder);

    private SchemCommand() {}

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        // level 2 (sama kayak /fill, /setblock): singleplayer butuh cheat aktif, server cuma OP
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("schem")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS));

        root.then(Commands.literal("list").executes(c -> Manager.list(c.getSource())));

        for (boolean instant : new boolean[]{false, true}) {
            RequiredArgumentBuilder<CommandSourceStack, String> name = Commands.argument("name", StringArgumentType.string()).suggests(NAMES)
                    .executes(c -> Manager.start(c.getSource(), StringArgumentType.getString(c, "name"), instant, false, Manager.Mode.BOTTOM))
                    .then(Commands.literal("air")
                            .executes(c -> Manager.start(c.getSource(), StringArgumentType.getString(c, "name"), instant, true, Manager.Mode.BOTTOM)));
            // print doang: /schem print <nama> <bottom|left|right|middle> [air]
            if (!instant) {
                for (Manager.Mode mode : Manager.Mode.values()) {
                    name.then(Commands.literal(mode.name().toLowerCase(Locale.ROOT))
                            .executes(c -> Manager.start(c.getSource(), StringArgumentType.getString(c, "name"), false, false, mode))
                            .then(Commands.literal("air")
                                    .executes(c -> Manager.start(c.getSource(), StringArgumentType.getString(c, "name"), false, true, mode))));
                }
            }
            root.then(Commands.literal(instant ? "paste" : "print").then(name));
        }

        root.then(Commands.literal("pause").executes(c -> Manager.pause(c.getSource(), true)));
        root.then(Commands.literal("resume").executes(c -> Manager.pause(c.getSource(), false)));
        root.then(Commands.literal("stop").executes(c -> Manager.stop(c.getSource())));
        root.then(Commands.literal("undo").executes(c -> Manager.undo(c.getSource())));
        root.then(Commands.literal("status").executes(c -> Manager.status(c.getSource())));

        root.then(Commands.literal("speed")
                .then(Commands.argument("blocks", IntegerArgumentType.integer(1, 100000))
                        .executes(c -> Manager.setSpeed(c.getSource(), IntegerArgumentType.getInteger(c, "blocks")))));
        root.then(Commands.literal("distance")
                .then(Commands.argument("blocks", IntegerArgumentType.integer(1, 256))
                        .executes(c -> Manager.setDistance(c.getSource(), IntegerArgumentType.getInteger(c, "blocks")))));

        LiteralArgumentBuilder<CommandSourceStack> face = Commands.literal("face");
        for (String dir : Layout.DIRS)
            face.then(Commands.literal(dir).executes(c -> Manager.setFace(c.getSource(), dir)));
        root.then(face);

        dispatcher.register(root);
    }
}
