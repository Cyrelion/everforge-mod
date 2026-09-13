package de.everforge.mod.server.opac;

import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import xaero.pac.common.server.ServerData;
import xaero.pac.common.server.player.config.PlayerConfig;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Temporary compatibility hotfix for Open Parties and Claims server claims.
 *
 * OPAC restores persisted server-claim forceload tickets from disk, but does
 * not refresh the synthetic server claim owner during startup. Normal player
 * and party owners are refreshed through their own lifecycle paths, which is
 * why their forceloads work while server-claim forceloads remain disabled.
 *
 * Remove this hotfix once upstream OPAC refreshes SERVER_CLAIM_UUID itself.
 */
public final class OpenPacServerForceloadHotfix {
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    private OpenPacServerForceloadHotfix() {
    }

    public static void install() {
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }

        NeoForge.EVENT_BUS.addListener(OpenPacServerForceloadHotfix::onServerStarted);
        System.out.println("[Everforge] OpenPAC server-claim forceload hotfix installed");
    }

    private static void onServerStarted(ServerStartedEvent event) {
        System.out.println("[Everforge] Refreshing OpenPAC server-claim forceload tickets");
        ServerData.from(event.getServer())
                .getForceLoadManager()
                .updateTicketsFor(PlayerConfig.SERVER_CLAIM_UUID, false);
    }
}
