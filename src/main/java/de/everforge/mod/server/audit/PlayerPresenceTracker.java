package de.everforge.mod.server.audit;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Persists exact player login/logout timestamps for Everforge Admin.
 *
 * The file is local server state below the Minecraft game directory and is
 * consumed read-only by the web container.
 */
public final class PlayerPresenceTracker {
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);
    private static final Path PRESENCE_FILE = FMLPaths.GAMEDIR.get()
            .resolve("everforge")
            .resolve("presence")
            .resolve("players.json");

    private PlayerPresenceTracker() {
    }

    public static void install() {
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }

        NeoForge.EVENT_BUS.addListener(PlayerPresenceTracker::onLogin);
        NeoForge.EVENT_BUS.addListener(PlayerPresenceTracker::onLogout);
        System.out.println("[Everforge] Player presence tracker installed: " + PRESENCE_FILE);
    }

    private static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            update(player, true);
        }
    }

    private static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            update(player, false);
        }
    }

    private static synchronized void update(ServerPlayer player, boolean online) {
        String now = Instant.now().toString();
        String uuid = player.getUUID().toString();

        JsonObject root = readRoot();
        JsonObject players = root.has("players") && root.get("players").isJsonObject()
                ? root.getAsJsonObject("players")
                : new JsonObject();

        JsonObject entry = players.has(uuid) && players.get(uuid).isJsonObject()
                ? players.getAsJsonObject(uuid)
                : new JsonObject();

        entry.addProperty("name", player.getGameProfile().getName());
        entry.addProperty("online", online);

        if (online) {
            entry.addProperty("last_login", now);
        } else {
            entry.addProperty("last_seen", now);
        }

        players.add(uuid, entry);
        root.add("players", players);
        root.addProperty("updated_at", now);

        writeRoot(root);
    }

    private static JsonObject readRoot() {
        if (!Files.isRegularFile(PRESENCE_FILE)) {
            return new JsonObject();
        }

        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(PRESENCE_FILE, StandardCharsets.UTF_8));
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        } catch (Exception e) {
            System.err.println("[Everforge] Could not read player presence state from "
                    + PRESENCE_FILE + ": " + e);
            return new JsonObject();
        }
    }

    private static void writeRoot(JsonObject root) {
        try {
            Files.createDirectories(PRESENCE_FILE.getParent());
            Path temp = PRESENCE_FILE.resolveSibling(PRESENCE_FILE.getFileName() + ".tmp");
            Files.writeString(temp, root.toString() + System.lineSeparator(), StandardCharsets.UTF_8);

            try {
                Files.move(temp, PRESENCE_FILE,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, PRESENCE_FILE, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            System.err.println("[Everforge] Could not write player presence state to "
                    + PRESENCE_FILE + ": " + e);
        }
    }
}
