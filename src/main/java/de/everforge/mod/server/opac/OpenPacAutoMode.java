package de.everforge.mod.server.opac;

import com.mojang.brigadier.arguments.BoolArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import xaero.pac.common.claims.api.SpecialClaimOwners;
import xaero.pac.common.claims.player.api.IPlayerChunkClaimAPI;
import xaero.pac.common.server.api.OpenPACServerAPI;
import xaero.pac.common.server.claims.api.IServerClaimsManagerAPI;
import xaero.pac.common.server.claims.protection.api.IChunkProtectionAPI;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Everforge automatic build-mode integration for OpenPAC server claims.
 *
 * When enabled, a player is switched to Creative only when all of these are
 * true:
 * - the current chunk is an OpenPAC server claim;
 * - OpenPAC grants the player effective full/build access to that chunk;
 * - the player is not in Spectator mode.
 *
 * Player claims, party claims and wilderness never activate AutoMode.
 * The previous game mode is stored in the player's persistent data and is
 * restored when the player leaves an eligible server claim, loses access,
 * AutoMode is disabled, or the mod no longer considers the location eligible.
 */
public final class OpenPacAutoMode {
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    private static final String CONFIG_FILE = "everforge-server.properties";
    private static final String CONFIG_KEY = "autoMode";

    private static final String NBT_ACTIVE = "everforge_auto_mode_active";
    private static final String NBT_PREVIOUS = "everforge_auto_mode_previous";

    /** Check twice per second. Claim boundaries are chunk based. */
    private static final int CHECK_INTERVAL_TICKS = 10;

    private static boolean enabled;
    private static int tickCounter;
    private static MinecraftServer activeServer;

    private OpenPacAutoMode() {
    }

    public static void install() {
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }

        NeoForge.EVENT_BUS.addListener(OpenPacAutoMode::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(OpenPacAutoMode::onServerStarted);
        NeoForge.EVENT_BUS.addListener(OpenPacAutoMode::onServerTick);
        NeoForge.EVENT_BUS.addListener(OpenPacAutoMode::onServerStopping);
        System.out.println("[Everforge] OpenPAC AutoMode installed");
    }

    private static void onServerStarted(ServerStartedEvent event) {
        activeServer = event.getServer();
        tickCounter = 0;
        enabled = loadConfig();
        System.out.println("[Everforge] AutoMode is " + (enabled ? "ON" : "OFF"));
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        if (event.getServer() != activeServer) {
            return;
        }

        /*
         * Restore players before the server writes their final player data.
         * This prevents auto-granted Creative from becoming their persisted
         * vanilla game mode if the server is shut down while they are inside
         * an eligible server claim.
         */
        restoreAll(event.getServer());
        activeServer = null;
        tickCounter = 0;
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        if (activeServer == null || event.getServer() != activeServer) {
            return;
        }

        if (++tickCounter < CHECK_INTERVAL_TICKS) {
            return;
        }
        tickCounter = 0;

        for (ServerPlayer player : activeServer.getPlayerList().getPlayers()) {
            updatePlayer(activeServer, player);
        }
    }

    private static void updatePlayer(MinecraftServer server, ServerPlayer player) {
        boolean autoActive = player.getPersistentData().getBoolean(NBT_ACTIVE);

        /* Spectator is always respected and cancels our ownership of mode. */
        if (player.gameMode.getGameModeForPlayer() == GameType.SPECTATOR) {
            if (autoActive) {
                clearAutoState(player);
            }
            return;
        }

        boolean eligible = enabled && isEligibleServerClaim(server, player);

        if (eligible) {
            if (!autoActive && player.gameMode.getGameModeForPlayer() != GameType.CREATIVE) {
                GameType previous = player.gameMode.getGameModeForPlayer();
                player.getPersistentData().putInt(NBT_PREVIOUS, previous.getId());
                player.getPersistentData().putBoolean(NBT_ACTIVE, true);
                player.setGameMode(GameType.CREATIVE);
            } else if (autoActive && player.gameMode.getGameModeForPlayer() != GameType.CREATIVE) {
                /* AutoMode owns the mode while the player remains eligible. */
                player.setGameMode(GameType.CREATIVE);
            }
            return;
        }

        if (autoActive) {
            restorePreviousMode(player);
        }
    }

    private static boolean isEligibleServerClaim(MinecraftServer server, ServerPlayer player) {
        OpenPACServerAPI api = OpenPACServerAPI.get(server);
        IServerClaimsManagerAPI claims = api.getServerClaimsManager();
        IChunkProtectionAPI protection = api.getChunkProtection();

        ResourceLocation dimension = player.level().dimension().location();
        ChunkPos chunk = player.chunkPosition();
        IPlayerChunkClaimAPI claim = claims.get(dimension, chunk.x, chunk.z);

        if (claim == null || !SpecialClaimOwners.SERVER.equals(claim.getPlayerId())) {
            return false;
        }

        return protection.hasChunkAccess(player, dimension, chunk.x, chunk.z);
    }

    private static void restorePreviousMode(ServerPlayer player) {
        int previousId = player.getPersistentData().getInt(NBT_PREVIOUS);
        GameType previous = GameType.byId(previousId);

        clearAutoState(player);

        if (previous == GameType.SPECTATOR) {
            return;
        }

        player.setGameMode(previous);
    }

    private static void clearAutoState(ServerPlayer player) {
        player.getPersistentData().remove(NBT_ACTIVE);
        player.getPersistentData().remove(NBT_PREVIOUS);
    }

    private static void restoreAll(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.getPersistentData().getBoolean(NBT_ACTIVE)) {
                restorePreviousMode(player);
            }
        }
    }

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("everforge")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("automode")
                                .executes(context -> {
                                    sendStatus(context.getSource().getServer(), context.getSource());
                                    return 1;
                                })
                                .then(Commands.literal("status")
                                        .executes(context -> {
                                            sendStatus(context.getSource().getServer(), context.getSource());
                                            return 1;
                                        }))
                                .then(Commands.argument("enabled", BoolArgumentType.bool())
                                        .executes(context -> {
                                            boolean newValue = BoolArgumentType.getBool(context, "enabled");
                                            setEnabled(context.getSource().getServer(), newValue);
                                            context.getSource().sendSuccess(
                                                    () -> Component.literal(
                                                            "Everforge AutoMode: " + (newValue ? "ON" : "OFF")
                                                    ),
                                                    true
                                            );
                                            return 1;
                                        }))
                                .then(Commands.literal("on")
                                        .executes(context -> {
                                            setEnabled(context.getSource().getServer(), true);
                                            context.getSource().sendSuccess(
                                                    () -> Component.literal("Everforge AutoMode: ON"),
                                                    true
                                            );
                                            return 1;
                                        }))
                                .then(Commands.literal("off")
                                        .executes(context -> {
                                            setEnabled(context.getSource().getServer(), false);
                                            context.getSource().sendSuccess(
                                                    () -> Component.literal("Everforge AutoMode: OFF"),
                                                    true
                                            );
                                            return 1;
                                        }))
                        )
        );
    }

    private static void sendStatus(MinecraftServer server, net.minecraft.commands.CommandSourceStack source) {
        source.sendSuccess(
                () -> Component.literal(
                        "Everforge AutoMode: " + (enabled ? "ON" : "OFF")
                                + " (Server Claims + Build-Rechte)"
                ),
                false
        );
    }

    private static void setEnabled(MinecraftServer server, boolean newValue) {
        enabled = newValue;
        saveConfig(newValue);

        if (!newValue) {
            restoreAll(server);
        } else {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                updatePlayer(server, player);
            }
        }
    }

    private static Path configPath() {
        return FMLPaths.CONFIGDIR.get().resolve(CONFIG_FILE);
    }

    private static boolean loadConfig() {
        Path path = configPath();
        Properties properties = new Properties();

        if (Files.exists(path)) {
            try (InputStream input = Files.newInputStream(path)) {
                properties.load(input);
            } catch (IOException e) {
                System.err.println("[Everforge] Could not read " + path + ": " + e);
            }
        }

        boolean value = Boolean.parseBoolean(properties.getProperty(CONFIG_KEY, "false"));

        if (!Files.exists(path)) {
            saveConfig(value);
        }

        return value;
    }

    private static void saveConfig(boolean value) {
        Path path = configPath();
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        Properties properties = new Properties();
        properties.setProperty(CONFIG_KEY, Boolean.toString(value));

        try {
            Files.createDirectories(path.getParent());
            try (OutputStream output = Files.newOutputStream(temporary)) {
                properties.store(output, "Everforge server settings");
            }
            try {
                Files.move(
                        temporary,
                        path,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                );
            } catch (IOException atomicMoveFailed) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            System.err.println("[Everforge] Could not write " + path + ": " + e);
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // Best effort only.
            }
        }
    }
}
