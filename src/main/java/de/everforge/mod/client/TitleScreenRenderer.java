package de.everforge.mod.client;
import de.everforge.mod.EverforgeMod;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;

public final class TitleScreenRenderer {
    public static final ResourceLocation BACKGROUND = ResourceLocation.fromNamespaceAndPath(
            EverforgeMod.MOD_ID, "textures/gui/title/background.png");
    public static final ResourceLocation LOGO = ResourceLocation.fromNamespaceAndPath(
            EverforgeMod.MOD_ID, "textures/gui/title/logo.png");

    // Source texture dimensions. Keep these in sync with the bundled PNG files.
    private static final int BACKGROUND_WIDTH = 1914;
    private static final int BACKGROUND_HEIGHT = 1076;
    private static final int LOGO_WIDTH = 1120;
    private static final int LOGO_HEIGHT = 350;

    private TitleScreenRenderer() {}

    /**
     * Draw the Everforge wallpaper using a CSS-like "cover" fit:
     * preserve aspect ratio, fill the full screen, crop only the overflow.
     */
    public static void renderBackground(GuiGraphics graphics, int screenWidth, int screenHeight) {
        if (screenWidth <= 0 || screenHeight <= 0) return;

        double targetAspect = (double) screenWidth / (double) screenHeight;
        double sourceAspect = (double) BACKGROUND_WIDTH / (double) BACKGROUND_HEIGHT;

        float u = 0.0F;
        float v = 0.0F;
        int regionWidth = BACKGROUND_WIDTH;
        int regionHeight = BACKGROUND_HEIGHT;

        if (targetAspect > sourceAspect) {
            regionHeight = Math.max(1, (int) Math.round(BACKGROUND_WIDTH / targetAspect));
            v = (BACKGROUND_HEIGHT - regionHeight) / 2.0F;
        } else if (targetAspect < sourceAspect) {
            regionWidth = Math.max(1, (int) Math.round(BACKGROUND_HEIGHT * targetAspect));
            u = (BACKGROUND_WIDTH - regionWidth) / 2.0F;
        }

        graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        graphics.blit(
                BACKGROUND,
                0, 0,
                screenWidth, screenHeight,
                u, v,
                regionWidth, regionHeight,
                BACKGROUND_WIDTH, BACKGROUND_HEIGHT
        );
        graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    /**
     * Draw a freely scalable Everforge logo, centered near the top.
     * The target width adapts to GUI width but is capped so it stays clear of the vanilla buttons.
     */
     public static void renderLogo(GuiGraphics graphics, int screenWidth, float alpha, int vanillaY) {
        if (screenWidth <= 0) return;

        int screenHeight = Minecraft.getInstance().getWindow().getGuiScaledHeight();

        // Maximum based on screen width.
        int maxWidthFromScreen = Math.round(screenWidth * 0.44F);

        // Also limit the logo to about 23% of the screen height.
        // Convert that permitted height back into a width using the logo aspect ratio.
        int maxWidthFromHeight = Math.round(
                screenHeight * 0.23F * (LOGO_WIDTH / (float) LOGO_HEIGHT)
            );

        int targetWidth = Math.max(
                1,
                Math.min(
                        500,
                        Math.min(maxWidthFromScreen, maxWidthFromHeight)
                )
            );

        int targetHeight = Math.max(
                1,
                Math.round(targetWidth * (LOGO_HEIGHT / (float) LOGO_WIDTH))
        );

        int x = (screenWidth - targetWidth) / 2;
        int y = 12;

        float safeAlpha = Math.max(0.0F, Math.min(1.0F, alpha));

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        graphics.setColor(1.0F, 1.0F, 1.0F, safeAlpha);
        graphics.blit(
                LOGO,
                x, y,
                targetWidth, targetHeight,
                0.0F, 0.0F,
                LOGO_WIDTH, LOGO_HEIGHT,
                LOGO_WIDTH, LOGO_HEIGHT
        );

        graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
    }
}
