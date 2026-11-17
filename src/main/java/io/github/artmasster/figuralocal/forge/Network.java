package io.github.artmasster.figuralocal.forge;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

/** The packet channel the tunnel uses. */
@SuppressWarnings("removal") // the two-argument ResourceLocation constructor is the 1.20.1 way
public final class Network {

    public static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
            .named(new ResourceLocation(FiguraLocalMod.MOD_ID, "tunnel"))
            .networkProtocolVersion(() -> "1")
            // Either side may be missing the mod: players without it and servers without it still connect.
            .clientAcceptedVersions(version -> true)
            .serverAcceptedVersions(version -> true)
            .simpleChannel();

    private Network() {
    }

    static void register() {
        CHANNEL.registerMessage(0, TunnelPacket.class, TunnelPacket::encode, TunnelPacket::decode, TunnelPacket::handle);
    }
}
