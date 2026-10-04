package de.everforge.mod.server.audit;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.CommandEvent;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * Local audit log for commands submitted in-game by privileged players.
 *
 * This intentionally does not send data over the network. Entries are appended
 * as JSON Lines below the Minecraft game directory so Everforge Admin can read
 * them through its existing read-only LIVE data mount.
 *
 * CommandEvent fires after parsing but before execution. Therefore the audit
 * record describes a submitted command, not a confirmed successful result.
 * Console and RCON commands are ignored because their command source is not a
 * ServerPlayer.
 */
public final class PlayerCommandAudit {
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);
    private static final Path AUDIT_FILE = FMLPaths.GAMEDIR.get()
            .resolve("everforge")
            .resolve("audit")
            .resolve("commands.jsonl");

    private static final CommandAuditStore STORE = new CommandAuditStore(AUDIT_FILE, PlayerCommandAudit::timestamp);
    private static ScheduledExecutorService maintenance;

    private PlayerCommandAudit() {
    }

    public static void install() {
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }

        NeoForge.EVENT_BUS.addListener(PlayerCommandAudit::onCommand);
        NeoForge.EVENT_BUS.addListener(PlayerCommandAudit::onServerStarted);
        NeoForge.EVENT_BUS.addListener(PlayerCommandAudit::onServerStopped);
        System.out.println("[Everforge] Player command audit installed: " + AUDIT_FILE);
    }

    private static void onCommand(CommandEvent event) {
        CommandSourceStack source = event.getParseResults().getContext().getSource();

        if (!(source.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        // Everforge audits administrative/OP-level player commands, not normal
        // player commands. Permission level 2 is Minecraft's moderator level.
        if (!source.hasPermission(2)) {
            return;
        }

        String command = event.getParseResults().getReader().getString();
        if (!command.startsWith("/")) {
            command = "/" + command;
        }

        JsonObject entry = new JsonObject();
        entry.addProperty("timestamp", Instant.now().toString());
        entry.addProperty("source", "ingame");
        entry.addProperty("status", "submitted");
        entry.addProperty("player", player.getGameProfile().getName());
        entry.addProperty("uuid", player.getUUID().toString());
        entry.addProperty("command", command);

        append(entry.toString());
    }

    private static Instant timestamp(String line) {
        try {
            JsonObject entry = JsonParser.parseString(line).getAsJsonObject();
            if (!entry.has("timestamp") || !entry.get("timestamp").isJsonPrimitive()
                    || !entry.get("timestamp").getAsJsonPrimitive().isString()) return null;
            return Instant.parse(entry.get("timestamp").getAsString());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static synchronized void onServerStarted(ServerStartedEvent event) {
        if (maintenance != null) return;
        maintenance = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "everforge-command-audit-retention");
            thread.setDaemon(true);
            return thread;
        });
        maintenance.scheduleWithFixedDelay(PlayerCommandAudit::maintain, 0, 1, TimeUnit.HOURS);
    }

    private static synchronized void onServerStopped(ServerStoppedEvent event) {
        if (maintenance != null) {
            maintenance.shutdownNow();
            maintenance = null;
        }
    }

    private static void maintain() {
        try {
            CommandAuditStore.Result result = STORE.maintain(Instant.now());
            if (result.invalid() > 0) System.err.println("[Everforge] Command audit: "
                    + result.invalid() + " entries need manual timestamp review");
            if (result.removed() > 0) System.out.println("[Everforge] Command audit removed "
                    + result.removed() + " entries older than 30 days");
        } catch (IOException | RuntimeException e) {
            System.err.println("[Everforge] Command audit maintenance failed: " + e);
        }
    }

    private static void append(String json) {
        try {
            STORE.append(json, Instant.now());
        } catch (IOException | RuntimeException e) {
            System.err.println("[Everforge] Could not append player command audit to "
                    + AUDIT_FILE + ": " + e);
        }
    }
}

