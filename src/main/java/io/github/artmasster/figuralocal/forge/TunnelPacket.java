package io.github.artmasster.figuralocal.forge;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** One tunnel frame, carried in a custom payload packet in either direction. */
public record TunnelPacket(byte[] frame) {

    /** Client-to-server custom payloads are capped at 32767 bytes, so frames stay below that. */
    private static final int MAX_FRAME = 32000;

    static void encode(TunnelPacket packet, FriendlyByteBuf buf) {
        buf.writeByteArray(packet.frame);
    }

    static TunnelPacket decode(FriendlyByteBuf buf) {
        return new TunnelPacket(buf.readByteArray(MAX_FRAME));
    }

    /** Runs on the network thread. The tunnel only queues bytes, so nothing here blocks. */
    static void handle(TunnelPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        if (context.getDirection() == NetworkDirection.PLAY_TO_SERVER) {
            ServerPlayer sender = context.getSender();
            if (sender != null)
                ServerHooks.frameFrom(sender, packet.frame);
        } else if (context.getDirection() == NetworkDirection.PLAY_TO_CLIENT) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> ClientBridge.frameFromServer(packet.frame, context.getNetworkManager()));
        }
        context.setPacketHandled(true);
    }
}
