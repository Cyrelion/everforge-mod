package de.everforge.mod.client.mixin;

import de.everforge.mod.client.TitleScreenRenderer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.PanoramaRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PanoramaRenderer.class)
public abstract class PanoramaRendererMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void everforge$renderStaticBackground(
            GuiGraphics graphics,
            int width,
            int height,
            float partialTick,
            float alpha,
            CallbackInfo ci
    ) {
        TitleScreenRenderer.renderBackground(graphics, width, height);
        ci.cancel();
    }
}
