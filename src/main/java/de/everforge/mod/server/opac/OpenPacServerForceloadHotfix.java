package de.everforge.mod.server.opac;

import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Temporary compatibility hotfix for Open Parties and Claims server claims.
 *
 * OPAC restores persisted server-claim forceload tickets from disk, but does
 * not refresh the synthetic server claim owner during startup. Calling OPAC's
 * updateTicketsFor(SERVER_CLAIM_UUID, false) fixes that, but enables every
 * persisted server-claim ticket synchronously. On Everforge this can mean
 * hundreds of chunks hitting storage at once.
 *
 * This hotfix therefore reuses OPAC's own ticket activation logic, but feeds
 * the persisted server tickets back in at a bounded rate after startup.
 *
 * Remove this hotfix once upstream OPAC restores server-claim tickets itself
 * in a suitably rate-limited way.
 */
public final class OpenPacServerForceloadHotfix {
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    /** One ticket every five server ticks = about four chunks per second. */
    private static final int TICK_INTERVAL = 5;
    private static final int TICKETS_PER_BATCH = 1;
    private static final int LOG_EVERY = 25;

    private static final Queue<Object> PENDING_TICKETS = new ArrayDeque<>();

    private static Object forceLoadManager;
    private static Method updateTicketMethod;
    private static MinecraftServer activeServer;
    private static int tickCounter;
    private static int totalTickets;
    private static int processedTickets;

    private OpenPacServerForceloadHotfix() {
    }

    public static void install() {
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }

        NeoForge.EVENT_BUS.addListener(OpenPacServerForceloadHotfix::onServerStarted);
        NeoForge.EVENT_BUS.addListener(OpenPacServerForceloadHotfix::onServerTick);
        NeoForge.EVENT_BUS.addListener(OpenPacServerForceloadHotfix::onServerStopping);
        System.out.println("[Everforge] OpenPAC server-claim forceload hotfix installed (rate limited)");
    }

    private static void onServerStarted(ServerStartedEvent event) {
        resetState();
        activeServer = event.getServer();

        try {
            Class<?> serverDataClass = Class.forName("xaero.pac.common.server.ServerData");
            Method fromMethod = serverDataClass.getMethod("from", MinecraftServer.class);
            Object serverData = fromMethod.invoke(null, activeServer);

            Method getForceLoadManagerMethod = serverData.getClass().getMethod("getForceLoadManager");
            forceLoadManager = getForceLoadManagerMethod.invoke(serverData);

            UUID serverClaimUuid = getServerClaimUuid();
            if (!ticketsShouldBeEnabled(forceLoadManager, serverClaimUuid)) {
                System.out.println("[Everforge] OpenPAC server-claim forceload is disabled; no tickets queued");
                resetState();
                return;
            }

            Object playerTickets = getPlayerTickets(forceLoadManager, serverClaimUuid);
            Method valuesMethod = playerTickets.getClass().getMethod("values");
            Iterable<?> tickets = (Iterable<?>) valuesMethod.invoke(playerTickets);

            for (Object ticket : tickets) {
                PENDING_TICKETS.add(ticket);
            }

            updateTicketMethod = findMethod(forceLoadManager.getClass(), "updateTicket", 2);
            updateTicketMethod.setAccessible(true);

            totalTickets = PENDING_TICKETS.size();
            System.out.println("[Everforge] Queued " + totalTickets
                    + " OpenPAC server-claim forceload tickets at "
                    + TICKETS_PER_BATCH + " ticket(s) every " + TICK_INTERVAL + " ticks");

            if (totalTickets == 0) {
                resetState();
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("[Everforge] Failed to prepare rate-limited OpenPAC server-claim forceloads: " + e);
            e.printStackTrace(System.err);
            resetState();
        }
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        if (activeServer == null || event.getServer() != activeServer || PENDING_TICKETS.isEmpty()) {
            return;
        }

        if (++tickCounter < TICK_INTERVAL) {
            return;
        }
        tickCounter = 0;

        try {
            int batchSize = Math.min(TICKETS_PER_BATCH, PENDING_TICKETS.size());
            for (int i = 0; i < batchSize; i++) {
                Object ticket = PENDING_TICKETS.remove();
                updateTicketMethod.invoke(forceLoadManager, true, ticket);
                processedTickets++;
            }

            if (PENDING_TICKETS.isEmpty()) {
                System.out.println("[Everforge] OpenPAC server-claim forceload restore complete: "
                        + processedTickets + "/" + totalTickets + " tickets processed");
                resetState();
            } else if (processedTickets % LOG_EVERY == 0) {
                System.out.println("[Everforge] OpenPAC server-claim forceload restore: "
                        + processedTickets + "/" + totalTickets + " tickets processed, "
                        + PENDING_TICKETS.size() + " remaining");
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("[Everforge] Failed while restoring OpenPAC server-claim forceload ticket: " + e);
            e.printStackTrace(System.err);
            resetState();
        }
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        if (event.getServer() == activeServer) {
            resetState();
        }
    }

    private static UUID getServerClaimUuid() throws ReflectiveOperationException {
        Class<?> playerConfigClass = Class.forName("xaero.pac.common.server.player.config.PlayerConfig");
        Field serverClaimUuidField = playerConfigClass.getField("SERVER_CLAIM_UUID");
        return (UUID) serverClaimUuidField.get(null);
    }

    private static Object getPlayerTickets(Object manager, UUID owner) throws ReflectiveOperationException {
        Method method = manager.getClass().getDeclaredMethod("getPlayerTickets", UUID.class);
        method.setAccessible(true);
        return method.invoke(manager, owner);
    }

    private static boolean ticketsShouldBeEnabled(Object manager, UUID owner) throws ReflectiveOperationException {
        Field configManagerField = manager.getClass().getDeclaredField("playerConfigManager");
        configManagerField.setAccessible(true);
        Object configManager = configManagerField.get(manager);

        Method getLoadedConfig = findMethod(configManager.getClass(), "getLoadedConfig", 1);
        Object ownerConfig = getLoadedConfig.invoke(configManager, owner);

        Method ticketsShouldBeEnabled = findMethod(manager.getClass(), "ticketsShouldBeEnabled", 2);
        ticketsShouldBeEnabled.setAccessible(true);
        return (boolean) ticketsShouldBeEnabled.invoke(manager, ownerConfig, false);
    }

    private static Method findMethod(Class<?> type, String name, int parameterCount) throws NoSuchMethodException {
        Class<?> current = type;
        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == parameterCount) {
                    method.setAccessible(true);
                    return method;
                }
            }
            current = current.getSuperclass();
        }
        throw new NoSuchMethodException(type.getName() + "." + name + " with " + parameterCount + " parameters");
    }

    private static void resetState() {
        PENDING_TICKETS.clear();
        forceLoadManager = null;
        updateTicketMethod = null;
        activeServer = null;
        tickCounter = 0;
        totalTickets = 0;
        processedTickets = 0;
    }
}
