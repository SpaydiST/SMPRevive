package macronux.smprevive;

import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;

public final class SMPReviveBootstrap implements PluginBootstrap {
    @Override
    public void bootstrap(BootstrapContext context) {
        context.getLifecycleManager().registerEventHandler(
            LifecycleEvents.DATAPACK_DISCOVERY.newHandler(event -> {
                try {
                    URL resource = SMPReviveBootstrap.class.getResource("/smprevive_advancements");
                    if (resource == null) {
                        throw new IllegalStateException("Bundled advancement datapack is missing");
                    }
                    event.registrar().discoverPack(resource.toURI(), "provided");
                } catch (URISyntaxException | IOException exception) {
                    throw new IllegalStateException(
                        "Failed to discover SMPRevive advancement datapack",
                        exception
                    );
                }
            })
        );
    }
}
