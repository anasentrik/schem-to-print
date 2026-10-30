package dev.schemprinter;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SchemPrinterMod implements ModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("schemprinter");

    @Override
    public void onInitialize() {
        Config.load(FabricLoader.getInstance().getConfigDir().resolve("schemprinter.properties"));

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                SchemCommand.register(dispatcher));
        ServerTickEvents.END_SERVER_TICK.register(Manager::tick);
        ServerLifecycleEvents.SERVER_STARTED.register(Manager::onStart);
        ServerLifecycleEvents.SERVER_STOPPING.register(Manager::onStop);
    }
}
