package de.everforge.mod.client.survey;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import de.everforge.mod.EverforgeMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

@EventBusSubscriber(modid = EverforgeMod.MOD_ID, value = Dist.CLIENT)
public final class MapSurveyClient {
    private enum Phase {
        ALIGN_TO_START,
        SCAN_LANE,
        SHIFT_LANE,
        COMPLETE
    }

    private static MapSurveyConfig config = MapSurveyConfig.load();
    private static boolean active;
    private static boolean paused;
    private static boolean firstLaneWestbound;
    private static int surveyZMin = config.zMin;
    private static int laneIndex;
    private static Phase phase = Phase.ALIGN_TO_START;
    private static long tickCounter;

    private MapSurveyClient() {}

    @SubscribeEvent
    private static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(
            Commands.literal("mapsurvey")
                .then(Commands.literal("start")
                    .executes(ctx -> start())
                    .then(Commands.literal("west").executes(ctx -> startFromCurrent(true)))
                    .then(Commands.literal("east").executes(ctx -> startFromCurrent(false))))
                .then(Commands.literal("pause").executes(ctx -> pause()))
                .then(Commands.literal("resume").executes(ctx -> resume()))
                .then(Commands.literal("stop").executes(ctx -> stop(false)))
                .then(Commands.literal("status").executes(ctx -> status()))
                .then(Commands.literal("reload").executes(ctx -> reload()))
                .then(Commands.literal("speed")
                    .then(Commands.argument("blocksPerSecond", DoubleArgumentType.doubleArg(1.0D, 400.0D))
                        .executes(ctx -> setSpeed(DoubleArgumentType.getDouble(ctx, "blocksPerSecond")))))
                .then(Commands.literal("spacing")
                    .then(Commands.argument("blocks", IntegerArgumentType.integer(16, 2048))
                        .executes(ctx -> setSpacing(IntegerArgumentType.getInteger(ctx, "blocks")))))
                .then(Commands.literal("altitude")
                    .then(Commands.argument("y", IntegerArgumentType.integer(-64, 1024))
                        .executes(ctx -> setAltitude(IntegerArgumentType.getInteger(ctx, "y")))))
                .then(Commands.literal("bounds")
                    .then(Commands.argument("xMin", IntegerArgumentType.integer())
                        .then(Commands.argument("zMin", IntegerArgumentType.integer())
                            .then(Commands.argument("xMax", IntegerArgumentType.integer())
                                .then(Commands.argument("zMax", IntegerArgumentType.integer())
                                    .executes(ctx -> setBounds(
                                        IntegerArgumentType.getInteger(ctx, "xMin"),
                                        IntegerArgumentType.getInteger(ctx, "zMin"),
                                        IntegerArgumentType.getInteger(ctx, "xMax"),
                                        IntegerArgumentType.getInteger(ctx, "zMax")
                                    )))))))
        );
    }

    @SubscribeEvent
    private static void onClientTick(ClientTickEvent.Post event) {
        if (!active || paused) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null) {
            return;
        }

        if (!player.isSpectator() && !player.getAbilities().flying) {
            pauseWithReason("Map survey paused: player is no longer flying or in spectator mode.");
            return;
        }

        if (laneIndex >= laneCount() || phase == Phase.COMPLETE) {
            stop(true);
            return;
        }

        Vec3 target = targetForCurrentPhase();
        moveTowards(player, target);

        if (player.position().distanceTo(target) <= config.arrivalTolerance) {
            advancePhase();
        }

        tickCounter++;
        if (tickCounter % 100L == 0L) {
            showProgress(false);
        }
    }

    private static int start() {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (!validatePlayerForStart(player)) {
            return 0;
        }

        config = MapSurveyConfig.load();
        surveyZMin = config.zMin;
        firstLaneWestbound = false;
        laneIndex = 0;
        phase = Phase.ALIGN_TO_START;
        paused = false;
        active = true;
        tickCounter = 0L;
        message("Map survey started from configured north-west corner. " + laneCount() + " lanes, spacing "
            + config.laneSpacing + ", speed " + format(config.speedMultiplier) + " blocks/s.");
        showProgress(false);
        return Command.SINGLE_SUCCESS;
    }

    private static int startFromCurrent(boolean westbound) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (!validatePlayerForStart(player)) {
            return 0;
        }

        config = MapSurveyConfig.load();
        int x = Mth.floor(player.getX());
        int z = Mth.floor(player.getZ());
        if (x < config.xMin || x > config.xMax || z < config.zMin || z > config.zMax) {
            message("Current position is outside the configured map survey bounds.");
            return 0;
        }

        surveyZMin = z;
        firstLaneWestbound = westbound;
        laneIndex = 0;
        phase = Phase.SCAN_LANE;
        paused = false;
        active = true;
        tickCounter = 0L;
        message("Map survey started from current position at X=" + x + ", Z=" + z + ", heading "
            + (westbound ? "west" : "east") + ". " + laneCount() + " lanes remain.");
        showProgress(false);
        return Command.SINGLE_SUCCESS;
    }

    private static boolean validatePlayerForStart(LocalPlayer player) {
        if (player == null) {
            message("Map survey cannot start before joining a world.");
            return false;
        }
        if (!player.isSpectator() && !player.getAbilities().flying) {
            message("Map survey requires spectator mode or active creative flight.");
            return false;
        }
        return true;
    }

    private static int pause() {
        if (!active || paused) {
            message("Map survey is not currently running.");
            return 0;
        }
        paused = true;
        stopMotion();
        message("Map survey paused.");
        return Command.SINGLE_SUCCESS;
    }

    private static int resume() {
        if (!active) {
            message("No active map survey to resume. Use /mapsurvey start.");
            return 0;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || (!player.isSpectator() && !player.getAbilities().flying)) {
            message("Map survey requires spectator mode or active creative flight.");
            return 0;
        }
        paused = false;
        message("Map survey resumed.");
        return Command.SINGLE_SUCCESS;
    }

    private static int stop(boolean completed) {
        if (!active && !completed) {
            message("Map survey is not active.");
            return 0;
        }
        active = false;
        paused = false;
        stopMotion();
        message(completed ? "Map survey complete." : "Map survey stopped.");
        return Command.SINGLE_SUCCESS;
    }

    private static int status() {
        showProgress(true);
        return Command.SINGLE_SUCCESS;
    }

    private static int reload() {
        config = MapSurveyConfig.load();
        if (!active) {
            surveyZMin = config.zMin;
        }
        message("Map survey config reloaded from " + MapSurveyConfig.PATH.getFileName() + ".");
        return Command.SINGLE_SUCCESS;
    }

    private static int setSpeed(double blocksPerSecond) {
        config.speedMultiplier = blocksPerSecond;
        config.save();
        message("Map survey speed set to " + format(blocksPerSecond) + " blocks/s.");
        return Command.SINGLE_SUCCESS;
    }

    private static int setSpacing(int spacing) {
        config.laneSpacing = spacing;
        config.save();
        message("Map survey lane spacing set to " + spacing + " blocks.");
        return Command.SINGLE_SUCCESS;
    }

    private static int setAltitude(int altitude) {
        config.altitude = altitude;
        config.save();
        message("Map survey altitude set to Y=" + altitude + ".");
        return Command.SINGLE_SUCCESS;
    }

    private static int setBounds(int xMin, int zMin, int xMax, int zMax) {
        config.setBounds(xMin, zMin, xMax, zMax);
        config.save();
        if (!active) {
            surveyZMin = config.zMin;
        }
        message("Map survey bounds set to X " + config.xMin + ".." + config.xMax
            + ", Z " + config.zMin + ".." + config.zMax + ".");
        return Command.SINGLE_SUCCESS;
    }

    private static int laneCount() {
        int range = Math.max(0, config.zMax - surveyZMin);
        return (int) Math.ceil(range / (double) config.laneSpacing) + 1;
    }

    private static int laneZ(int index) {
        return Math.min(config.zMax, surveyZMin + index * config.laneSpacing);
    }

    private static boolean currentLaneEastbound() {
        boolean evenLane = laneIndex % 2 == 0;
        return firstLaneWestbound ? !evenLane : evenLane;
    }

    private static Vec3 targetForCurrentPhase() {
        int z = laneZ(laneIndex);
        boolean eastbound = currentLaneEastbound();
        return switch (phase) {
            case ALIGN_TO_START -> new Vec3(config.xMin + 0.5D, config.altitude, surveyZMin + 0.5D);
            case SCAN_LANE -> new Vec3((eastbound ? config.xMax : config.xMin) + 0.5D, config.altitude, z + 0.5D);
            case SHIFT_LANE -> new Vec3((eastbound ? config.xMax : config.xMin) + 0.5D, config.altitude,
                laneZ(Math.min(laneIndex + 1, laneCount() - 1)) + 0.5D);
            case COMPLETE -> Minecraft.getInstance().player != null
                ? Minecraft.getInstance().player.position()
                : Vec3.ZERO;
        };
    }

    private static void advancePhase() {
        switch (phase) {
            case ALIGN_TO_START -> phase = Phase.SCAN_LANE;
            case SCAN_LANE -> {
                if (laneIndex >= laneCount() - 1) {
                    phase = Phase.COMPLETE;
                    stop(true);
                } else {
                    phase = Phase.SHIFT_LANE;
                }
            }
            case SHIFT_LANE -> {
                laneIndex++;
                phase = Phase.SCAN_LANE;
            }
            case COMPLETE -> stop(true);
        }
    }

    private static void moveTowards(LocalPlayer player, Vec3 target) {
        Vec3 delta = target.subtract(player.position());
        double horizontalDistance = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        double distance = delta.length();
        if (distance < 0.001D) {
            player.setDeltaMovement(Vec3.ZERO);
            return;
        }

        double blocksPerTick = config.speedMultiplier / 20.0D;
        Vec3 direction = delta.normalize();
        double vertical = Mth.clamp(direction.y * blocksPerTick, -1.5D, 1.5D);
        player.setDeltaMovement(direction.x * blocksPerTick, vertical, direction.z * blocksPerTick);

        if (horizontalDistance > 0.01D) {
            float yaw = (float) (Math.toDegrees(Math.atan2(-delta.x, delta.z)));
            player.setYRot(yaw);
            player.setYHeadRot(yaw);
            player.setXRot(0.0F);
        }
    }

    private static void showProgress(boolean detailed) {
        int lanes = laneCount();
        double pct = lanes <= 1 ? 100.0D : Math.min(100.0D, laneIndex * 100.0D / (lanes - 1));
        String state = !active ? "STOPPED" : paused ? "PAUSED" : "RUNNING";
        String text = "Map survey " + state + " | lane " + (laneIndex + 1) + "/" + lanes
            + " | " + String.format(java.util.Locale.ROOT, "%.1f", pct) + "%"
            + " | speed " + format(config.speedMultiplier) + " b/s";
        if (detailed) {
            text += " | X " + config.xMin + ".." + config.xMax
                + " | Z " + surveyZMin + ".." + config.zMax
                + " | Y " + config.altitude + " | spacing " + config.laneSpacing;
        }
        message(text);
    }

    private static void pauseWithReason(String reason) {
        paused = true;
        stopMotion();
        message(reason);
    }

    private static void stopMotion() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            player.setDeltaMovement(Vec3.ZERO);
        }
    }

    private static void message(String text) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            player.displayClientMessage(Component.literal(text), false);
        }
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }
}
