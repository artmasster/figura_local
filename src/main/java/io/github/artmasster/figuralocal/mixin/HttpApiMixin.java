package io.github.artmasster.figuralocal.mixin;

import io.github.artmasster.figuralocal.core.BackendOverride;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Points Figura's HTTP calls at the local proxy while the player is on a FiguraLocal server.
 * The target is named by string, so this compiles without Figura and does nothing when Figura is absent.
 */
@Pseudo
@Mixin(targets = "org.figuramc.figura.backend2.HttpAPI", remap = false)
public abstract class HttpApiMixin {

    @Inject(method = "getBackendAddress()Ljava/lang/String;", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void figuralocal$useLocalBackend(CallbackInfoReturnable<String> cir) {
        String base = BackendOverride.httpBase();
        if (base != null)
            cir.setReturnValue(base);
    }
}
