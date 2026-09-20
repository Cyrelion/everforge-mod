package de.everforge.mod.server.audit;

import com.google.gson.JsonObject;
import de.everforge.mod.EverforgeMod;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.CommandEvent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

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

    private PlayerCommandAudit() {
    }

    public static void install() {
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }

        NeoForge.EVENT_BUS.addListener(PlayerCommandAudit::onCommand);
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

    private static void append(String json) {
        try {
            Files.createDirectories(AUDIT_FILE.getParent());
            Files.writeString(
                    AUDIT_FILE,
                    json + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND
            );
        } catch (IOException e) {
            System.err.println("[Everforge] Could not append player command audit to "
                    + AUDIT_FILE + ": " + e);
        }
    }
}
