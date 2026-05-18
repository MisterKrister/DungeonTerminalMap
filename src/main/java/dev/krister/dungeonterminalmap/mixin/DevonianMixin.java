package dev.krister.dungeonterminalmap.mixin;

import com.github.synnerz.devonian.Devonian;
import dev.krister.dungeonterminalmap.DungeonTerminalMapAddon;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Devonian.class)
public class DevonianMixin {
    @Inject(method = "onInitializeClient", at = @At("HEAD"), remap = false)
    private void dungeonterminalmap$preInit(CallbackInfo ci) {
        DungeonTerminalMapAddon.INSTANCE.registerWithDevonian();
    }
}
