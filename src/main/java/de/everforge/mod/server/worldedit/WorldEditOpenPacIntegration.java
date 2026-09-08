package de.everforge.mod.server.worldedit;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.event.extent.EditSessionEvent;
import com.sk89q.worldedit.extension.platform.Actor;
import com.sk89q.worldedit.util.eventbus.Subscribe;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import xaero.pac.common.server.api.OpenPACServerAPI;
import xaero.pac.common.server.claims.protection.api.IChunkProtectionAPI;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Installs an OpenPAC-backed WorldEdit extent filter.
 *
 * The guard is attached to BEFORE_CHANGE and BEFORE_HISTORY. WorldEdit edits
 * are therefore filtered per block/chunk instead of rejecting an entire
 * selection when only part of it lies in a protected claim.
 */
public final class WorldEditOpenPacIntegration {
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);
    private static final WorldEditOpenPacIntegration INSTANCE =
            new WorldEditOpenPacIntegration();

    private WorldEditOpenPacIntegration() {
    }

    public static void install() {
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }

        WorldEdit.getInstance().getEventBus().register(INSTANCE);
        System.out.println(
                "[Everforge] WorldEdit/OpenPAC claim guard installed"
        );
    }

    @Subscribe
    public void onEditSession(EditSessionEvent event) {
        Actor actor = event.getActor();

        if (actor == null || !actor.isPlayer()) {
            return;
        }

        if (event.getStage() != EditSession.Stage.BEFORE_CHANGE
                && event.getStage() != EditSession.Stage.BEFORE_HISTORY) {
            return;
        }

        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }

        ServerPlayer player =
                server.getPlayerList().getPlayer(actor.getUniqueId());
        if (player == null) {
            return;
        }

        /*
         * WorldEdit's NeoForge world id is a platform identifier such as:
         *   Everforge_minecraft:overworld
         * and is not guaranteed to be a Minecraft ResourceLocation.
         *
         * Use the authoritative dimension from the live ServerPlayer.
         */
        ResourceLocation dimension =
                player.level().dimension().location();

        IChunkProtectionAPI protection =
                OpenPACServerAPI.get(server).getChunkProtection();

        OpenPacClaimExtent.EditSessionStage guardStage =
                event.getStage() == EditSession.Stage.BEFORE_CHANGE
                        ? OpenPacClaimExtent.EditSessionStage.BEFORE_CHANGE
                        : OpenPacClaimExtent.EditSessionStage.BEFORE_HISTORY;

        event.setExtent(new OpenPacClaimExtent(
                event.getExtent(),
                player,
                dimension,
                protection,
                guardStage
        ));
    }
}
