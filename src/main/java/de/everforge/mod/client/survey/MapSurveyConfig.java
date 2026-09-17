package de.everforge.mod.client.survey;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import net.neoforged.fml.loading.FMLPaths;

final class MapSurveyConfig {
    static final Path PATH = FMLPaths.CONFIGDIR.get().resolve("everforge-map-survey.properties");

    int xMin = -16000;
    int xMax = 20000;
    int zMin = -10000;
    int zMax = 20000;
    int altitude = 180;
    int laneSpacing = 272;
    double speedMultiplier = 4.0D;
    double arrivalTolerance = 6.0D;

    static MapSurveyConfig load() {
        MapSurveyConfig config = new MapSurveyConfig();
        if (!Files.isRegularFile(PATH)) {
            config.save();
            return config;
        }

        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(PATH)) {
            properties.load(input);
            config.xMin = parseInt(properties, "xMin", config.xMin);
            config.xMax = parseInt(properties, "xMax", config.xMax);
            config.zMin = parseInt(properties, "zMin", config.zMin);
            config.zMax = parseInt(properties, "zMax", config.zMax);
            config.altitude = parseInt(properties, "altitude", config.altitude);
            config.laneSpacing = Math.max(16, parseInt(properties, "laneSpacing", config.laneSpacing));
            config.speedMultiplier = clamp(parseDouble(properties, "speedMultiplier", config.speedMultiplier), 0.25D, 20.0D);
            config.arrivalTolerance = clamp(parseDouble(properties, "arrivalTolerance", config.arrivalTolerance), 1.0D, 64.0D);
        } catch (IOException ignored) {
            // Keep defaults/current values if the config cannot be read.
        }

        config.normalizeBounds();
        return config;
    }

    void save() {
        normalizeBounds();
        Properties properties = new Properties();
        properties.setProperty("xMin", Integer.toString(xMin));
        properties.setProperty("xMax", Integer.toString(xMax));
        properties.setProperty("zMin", Integer.toString(zMin));
        properties.setProperty("zMax", Integer.toString(zMax));
        properties.setProperty("altitude", Integer.toString(altitude));
        properties.setProperty("laneSpacing", Integer.toString(laneSpacing));
        properties.setProperty("speedMultiplier", Double.toString(speedMultiplier));
        properties.setProperty("arrivalTolerance", Double.toString(arrivalTolerance));

        try {
            Files.createDirectories(PATH.getParent());
            try (OutputStream output = Files.newOutputStream(PATH)) {
                properties.store(output, "Everforge client map survey settings");
            }
        } catch (IOException ignored) {
            // Runtime commands still work for the current session if persistence fails.
        }
    }

    int laneCount() {
        int range = zMax - zMin;
        return (int) Math.ceil(range / (double) laneSpacing) + 1;
    }

    int laneZ(int laneIndex) {
        return Math.min(zMax, zMin + laneIndex * laneSpacing);
    }

    void setBounds(int newXMin, int newZMin, int newXMax, int newZMax) {
        xMin = Math.min(newXMin, newXMax);
        xMax = Math.max(newXMin, newXMax);
        zMin = Math.min(newZMin, newZMax);
        zMax = Math.max(newZMin, newZMax);
    }

    private void normalizeBounds() {
        if (xMin > xMax) {
            int swap = xMin;
            xMin = xMax;
            xMax = swap;
        }
        if (zMin > zMax) {
            int swap = zMin;
            zMin = zMax;
            zMax = swap;
        }
        laneSpacing = Math.max(16, laneSpacing);
    }

    private static int parseInt(Properties properties, String key, int fallback) {
        try {
            return Integer.parseInt(properties.getProperty(key, Integer.toString(fallback)).trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static double parseDouble(Properties properties, String key, double fallback) {
        try {
            return Double.parseDouble(properties.getProperty(key, Double.toString(fallback)).trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
