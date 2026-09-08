package de.everforge.mod.client.mixin;

import net.minecraft.client.gui.components.SplashRenderer;
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin {
    @Shadow
    private SplashRenderer splash;

    @Inject(method = "init", at = @At("RETURN"))
    private void everforge$removeSplash(CallbackInfo ci) {
        this.splash = null;
    }
}
