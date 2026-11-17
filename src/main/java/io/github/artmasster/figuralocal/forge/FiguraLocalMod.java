package io.github.artmasster.figuralocal.forge;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;

@Mod(FiguraLocalMod.MOD_ID)
public final class FiguraLocalMod {

    public static final String MOD_ID = "figuralocal";

    public FiguraLocalMod() {
        Network.register();
        MinecraftForge.EVENT_BUS.register(new ServerHooks());
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> ClientBridge::register);
    }
}
