package de.everforge.mod.server.create;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.simibubi.create.Create;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBehaviour;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlock;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockEntity;
import com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBehaviour;
import com.simibubi.create.content.logistics.packagerLink.LogisticsManager;
import com.simibubi.create.content.logistics.packagerLink.PackagerLinkBlockEntity;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Everforge convenience layer for Create logistics networks.
 *
 * Create keeps using its native UUID based network model. Everforge only adds:
 * - persistent human readable names for those UUIDs;
 * - commands for inspecting and renaming the network of the looked-at component;
 * - safe reassignment of an existing component to a named/copied network.
 *
 * No Factory Gauge filter, threshold, recipe or connection settings are changed.
 */
public final class CreateLogisticsNetworkManager {
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    private static final String CONFIG_FILE = "everforge-logistics-networks.properties";
    private static final String CLIPBOARD_KEY = "everforge_logistics_network_clipboard";

    private static final Map<UUID, String> NETWORK_NAMES = new LinkedHashMap<>();

    private static MinecraftServer activeServer;

    private CreateLogisticsNetworkManager() {
    }

    public static void install() {
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }

        NeoForge.EVENT_BUS.addListener(CreateLogisticsNetworkManager::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(CreateLogisticsNetworkManager::onServerStarted);
        NeoForge.EVENT_BUS.addListener(CreateLogisticsNetworkManager::onServerStopping);
        System.out.println("[Everforge] Create Logistics Network Manager installed");
    }

    private static void onServerStarted(ServerStartedEvent event) {
        activeServer = event.getServer();
        loadNames();
        System.out.println("[Everforge] Loaded " + NETWORK_NAMES.size() + " named Create logistics network(s)");
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        if (activeServer != event.getServer()) {
            return;
        }

        saveNames();
        activeServer = null;
        NETWORK_NAMES.clear();
    }

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("everforge")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("logistics")
                                .then(Commands.literal("status")
                                        .executes(context -> status(context.getSource())))
                                .then(Commands.literal("name")
                                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                                .executes(context -> nameCurrent(
                                                        context.getSource(),
                                                        StringArgumentType.getString(context, "name")
                                                ))))
                                .then(Commands.literal("move")
                                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                                .suggests((context, builder) -> {
                                                    synchronized (NETWORK_NAMES) {
                                                        NETWORK_NAMES.values().stream()
                                                                .sorted(String.CASE_INSENSITIVE_ORDER)
                                                                .forEach(builder::suggest);
                                                    }
                                                    return builder.buildFuture();
                                                })
                                                .executes(context -> moveCurrent(
                                                        context.getSource(),
                                                        StringArgumentType.getString(context, "name")
                                                ))))
                                .then(Commands.literal("copy")
                                        .executes(context -> copyCurrent(context.getSource())))
                                .then(Commands.literal("paste")
                                        .executes(context -> pasteCurrent(context.getSource())))
                                .then(Commands.literal("list")
                                        .executes(context -> listNetworks(context.getSource())))
                                .then(Commands.literal("unname")
                                        .executes(context -> unnameCurrent(context.getSource())))
                        )
        );
    }

    private static int status(CommandSourceStack source) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) {
            return 0;
        }

        NetworkTarget target = resolveTarget(player);
        if (target == null) {
            source.sendFailure(Component.literal(
                    "Look directly at a Create Factory Gauge, Stock Link, Stock Ticker or another logistics-linked block."
            ));
            return 0;
        }

        UUID network = target.network();
        String name = getName(network);
        source.sendSuccess(
                () -> Component.literal("Create logistics: ")
                        .append(Component.literal(target.description()).withStyle(ChatFormatting.AQUA))
                        .append(Component.literal(" -> "))
                        .append(Component.literal(name == null ? "(unnamed)" : name)
                                .withStyle(name == null ? ChatFormatting.GRAY : ChatFormatting.GREEN))
                        .append(Component.literal(" [" + network + "]").withStyle(ChatFormatting.DARK_GRAY)),
                false
        );
        return 1;
    }

    private static int nameCurrent(CommandSourceStack source, String rawName) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) {
            return 0;
        }

        NetworkTarget target = resolveTarget(player);
        if (target == null) {
            source.sendFailure(Component.literal("No supported Create logistics component targeted."));
            return 0;
        }

        String name = normalizeName(rawName);
        if (name == null) {
            source.sendFailure(Component.literal("Network name must contain at least one visible character."));
            return 0;
        }

        UUID conflict = findByName(name);
        if (conflict != null && !conflict.equals(target.network())) {
            source.sendFailure(Component.literal(
                    "The name '" + name + "' is already assigned to another logistics network."
            ));
            return 0;
        }

        synchronized (NETWORK_NAMES) {
            NETWORK_NAMES.put(target.network(), name);
        }
        saveNames();

        source.sendSuccess(
                () -> Component.literal("Named Create logistics network ")
                        .append(Component.literal(name).withStyle(ChatFormatting.GREEN))
                        .append(Component.literal(" [" + target.network() + "]").withStyle(ChatFormatting.DARK_GRAY)),
                true
        );
        return 1;
    }

    private static int unnameCurrent(CommandSourceStack source) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) {
            return 0;
        }

        NetworkTarget target = resolveTarget(player);
        if (target == null) {
            source.sendFailure(Component.literal("No supported Create logistics component targeted."));
            return 0;
        }

        String oldName;
        synchronized (NETWORK_NAMES) {
            oldName = NETWORK_NAMES.remove(target.network());
        }

        if (oldName == null) {
            source.sendFailure(Component.literal("This logistics network does not have an Everforge name."));
            return 0;
        }

        saveNames();
        source.sendSuccess(
                () -> Component.literal("Removed logistics network name ")
                        .append(Component.literal(oldName).withStyle(ChatFormatting.YELLOW))
                        .append(Component.literal(". The Create UUID itself was not changed.")),
                true
        );
        return 1;
    }

    private static int moveCurrent(CommandSourceStack source, String rawName) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) {
            return 0;
        }

        NetworkTarget target = resolveTarget(player);
        if (target == null) {
            source.sendFailure(Component.literal("No supported Create logistics component targeted."));
            return 0;
        }

        String requested = normalizeName(rawName);
        UUID destination = requested == null ? null : findByName(requested);
        if (destination == null) {
            source.sendFailure(Component.literal("Unknown logistics network name: " + rawName));
            return 0;
        }

        return moveTarget(source, target, destination, getName(destination));
    }

    private static int copyCurrent(CommandSourceStack source) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) {
            return 0;
        }

        NetworkTarget target = resolveTarget(player);
        if (target == null) {
            source.sendFailure(Component.literal("No supported Create logistics component targeted."));
            return 0;
        }

        player.getPersistentData().putUUID(CLIPBOARD_KEY, target.network());

        String name = getName(target.network());
        source.sendSuccess(
                () -> Component.literal("Copied logistics network ")
                        .append(Component.literal(name == null ? target.network().toString() : name)
                                .withStyle(ChatFormatting.GREEN))
                        .append(Component.literal(". Look at another component and run /everforge logistics paste.")),
                false
        );
        return 1;
    }

    private static int pasteCurrent(CommandSourceStack source) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) {
            return 0;
        }

        if (!player.getPersistentData().hasUUID(CLIPBOARD_KEY)) {
            source.sendFailure(Component.literal(
                    "No logistics network copied yet. Look at a component and run /everforge logistics copy first."
            ));
            return 0;
        }

        NetworkTarget target = resolveTarget(player);
        if (target == null) {
            source.sendFailure(Component.literal("No supported Create logistics component targeted."));
            return 0;
        }

        UUID destination = player.getPersistentData().getUUID(CLIPBOARD_KEY);
        return moveTarget(source, target, destination, getName(destination));
    }

    private static int moveTarget(
            CommandSourceStack source,
            NetworkTarget target,
            UUID destination,
            String destinationName
    ) {
        UUID previous = target.network();
        if (previous.equals(destination)) {
            source.sendSuccess(
                    () -> Component.literal("This component is already on that logistics network."),
                    false
            );
            return 1;
        }

        target.moveTo(destination);

        String previousName = getName(previous);
        String from = previousName == null ? previous.toString() : previousName;
        String to = destinationName == null ? destination.toString() : destinationName;

        source.sendSuccess(
                () -> Component.literal("Moved ")
                        .append(Component.literal(target.description()).withStyle(ChatFormatting.AQUA))
                        .append(Component.literal(" from "))
                        .append(Component.literal(from).withStyle(ChatFormatting.YELLOW))
                        .append(Component.literal(" to "))
                        .append(Component.literal(to).withStyle(ChatFormatting.GREEN))
                        .append(Component.literal(". Other component settings were left unchanged.")),
                true
        );
        return 1;
    }

    private static int listNetworks(CommandSourceStack source) {
        List<Map.Entry<UUID, String>> entries;
        synchronized (NETWORK_NAMES) {
            entries = new ArrayList<>(NETWORK_NAMES.entrySet());
        }

        if (entries.isEmpty()) {
            source.sendSuccess(
                    () -> Component.literal("No named Create logistics networks yet."),
                    false
            );
            return 1;
        }

        entries.sort(Comparator.comparing(Map.Entry::getValue, String.CASE_INSENSITIVE_ORDER));
        source.sendSuccess(
                () -> Component.literal("Named Create logistics networks (" + entries.size() + "):")
                        .withStyle(ChatFormatting.GOLD),
                false
        );

        for (Map.Entry<UUID, String> entry : entries) {
            source.sendSuccess(
                    () -> Component.literal(" - ")
                            .append(Component.literal(entry.getValue()).withStyle(ChatFormatting.GREEN))
                            .append(Component.literal(" [" + entry.getKey() + "]").withStyle(ChatFormatting.DARK_GRAY)),
                    false
            );
        }
        return entries.size();
    }

    private static ServerPlayer requirePlayer(CommandSourceStack source) {
        try {
            return source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.literal("This command must be run by an in-game player."));
            return null;
        }
    }

    private static NetworkTarget resolveTarget(ServerPlayer player) {
        double range = player.getAttributeValue(Attributes.BLOCK_INTERACTION_RANGE) + 1.0D;
        HitResult result = player.pick(range, 1.0F, false);
        if (!(result instanceof BlockHitResult hit) || hit.getType() == HitResult.Type.MISS) {
            return null;
        }

        BlockPos pos = hit.getBlockPos();
        BlockEntity blockEntity = player.level().getBlockEntity(pos);
        if (blockEntity == null) {
            return null;
        }

        if (blockEntity instanceof FactoryPanelBlockEntity panelBlockEntity) {
            FactoryPanelBlock.PanelSlot slot = FactoryPanelBlock.getTargetedSlot(
                    pos,
                    player.level().getBlockState(pos),
                    hit.getLocation()
            );
            FactoryPanelBehaviour panel = panelBlockEntity.panels.get(slot);
            if (panel == null || !panel.isActive()) {
                return null;
            }

            String description = "Factory Gauge " + slot.getSerializedName();
            return new NetworkTarget(
                    panel.network,
                    description,
                    destination -> moveFactoryPanel(panelBlockEntity, panel, destination)
            );
        }

        LogisticallyLinkedBehaviour behaviour = BlockEntityBehaviour.get(
                player.level(),
                pos,
                LogisticallyLinkedBehaviour.TYPE
        );
        if (behaviour == null) {
            return null;
        }

        String description = blockEntity.getBlockState().getBlock().getName().getString();
        return new NetworkTarget(
                behaviour.freqId,
                description,
                destination -> moveLinkedBehaviour(blockEntity, behaviour, destination)
        );
    }

    private static void moveFactoryPanel(
            FactoryPanelBlockEntity blockEntity,
            FactoryPanelBehaviour panel,
            UUID destination
    ) {
        UUID previous = panel.network;
        panel.setNetwork(destination);

        invalidateNetworkCaches(previous);
        invalidateNetworkCaches(destination);

        blockEntity.setChanged();
        blockEntity.notifyUpdate();
    }

    private static void moveLinkedBehaviour(
            BlockEntity blockEntity,
            LogisticallyLinkedBehaviour behaviour,
            UUID destination
    ) {
        UUID previous = behaviour.freqId;
        if (previous.equals(destination)) {
            return;
        }

        /*
         * The normal Create behaviour cache is keyed by network UUID, so move
         * the live entry as well as changing the persisted UUID.
         */
        LogisticallyLinkedBehaviour.remove(behaviour);

        if (blockEntity instanceof PackagerLinkBlockEntity packagerLink) {
            GlobalPos position = GlobalPos.of(blockEntity.getLevel().dimension(), blockEntity.getBlockPos());

            /*
             * Stock Links are also tracked by Create's GlobalLogisticsManager.
             * Move the global registration so unloaded-link counts, ownership
             * and lock state do not retain a ghost entry on the old network.
             */
            Create.LOGISTICS.linkRemoved(previous, position);
            behaviour.freqId = destination;
            Create.LOGISTICS.linkAdded(destination, position, packagerLink.placedBy);
            Create.LOGISTICS.linkLoaded(destination, position);
        } else {
            behaviour.freqId = destination;
        }

        LogisticallyLinkedBehaviour.keepAlive(behaviour);

        invalidateNetworkCaches(previous);
        invalidateNetworkCaches(destination);

        blockEntity.setChanged();
        if (blockEntity instanceof SmartBlockEntity smartBlockEntity) {
            smartBlockEntity.sendData();
        } else if (blockEntity.getLevel() != null) {
            blockEntity.getLevel().sendBlockUpdated(
                    blockEntity.getBlockPos(),
                    blockEntity.getBlockState(),
                    blockEntity.getBlockState(),
                    3
            );
        }
    }

    private static void invalidateNetworkCaches(UUID network) {
        LogisticsManager.SUMMARIES.invalidate(network);
        LogisticsManager.ACCURATE_SUMMARIES.invalidate(network);
    }

    private static String getName(UUID network) {
        synchronized (NETWORK_NAMES) {
            return NETWORK_NAMES.get(network);
        }
    }

    private static UUID findByName(String name) {
        String normalized = name.toLowerCase(Locale.ROOT);
        synchronized (NETWORK_NAMES) {
            for (Map.Entry<UUID, String> entry : NETWORK_NAMES.entrySet()) {
                if (entry.getValue().toLowerCase(Locale.ROOT).equals(normalized)) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }

    private static String normalizeName(String rawName) {
        if (rawName == null) {
            return null;
        }
        String value = rawName.trim().replaceAll("\\s+", " ");
        return value.isBlank() ? null : value;
    }

    private static Path configPath() {
        return FMLPaths.CONFIGDIR.get().resolve(CONFIG_FILE);
    }

    private static void loadNames() {
        synchronized (NETWORK_NAMES) {
            NETWORK_NAMES.clear();

            Path path = configPath();
            if (!Files.exists(path)) {
                return;
            }

            Properties properties = new Properties();
            try (InputStream input = Files.newInputStream(path)) {
                properties.load(input);
            } catch (IOException e) {
                System.err.println("[Everforge] Could not read " + path + ": " + e);
                return;
            }

            for (String key : properties.stringPropertyNames()) {
                try {
                    UUID id = UUID.fromString(key);
                    String name = normalizeName(properties.getProperty(key));
                    if (name != null) {
                        NETWORK_NAMES.put(id, name);
                    }
                } catch (IllegalArgumentException ignored) {
                    System.err.println("[Everforge] Ignoring invalid logistics network UUID in " + path + ": " + key);
                }
            }
        }
    }

    private static void saveNames() {
        Path path = configPath();
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");

        Properties properties = new Properties();
        synchronized (NETWORK_NAMES) {
            NETWORK_NAMES.forEach((uuid, name) -> properties.setProperty(uuid.toString(), name));
        }

        try {
            Files.createDirectories(path.getParent());
            try (OutputStream output = Files.newOutputStream(temporary)) {
                properties.store(
                        output,
                        "Everforge Create logistics network names. UUIDs are Create's native network identifiers."
                );
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

    @FunctionalInterface
    private interface NetworkMover {
        void move(UUID destination);
    }

    private record NetworkTarget(UUID network, String description, NetworkMover mover) {
        void moveTo(UUID destination) {
            mover.move(destination);
        }
    }
}
