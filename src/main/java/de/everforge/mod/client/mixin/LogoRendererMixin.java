package de.everforge.mod.client.mixin;

import de.everforge.mod.client.TitleScreenRenderer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.LogoRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LogoRenderer.class)
public abstract class LogoRendererMixin {
    @Inject(method = "renderLogo(Lnet/minecraft/client/gui/GuiGraphics;IFI)V", at = @At("HEAD"), cancellable = true)
    private void everforge$renderLogo(
            GuiGraphics graphics,
            int screenWidth,
            float alpha,
            int y,
            CallbackInfo ci
    ) {
        TitleScreenRenderer.renderLogo(graphics, screenWidth, alpha, y);
        ci.cancel();
    }
}
