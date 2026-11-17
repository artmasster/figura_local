package io.github.artmasster.figuralocal.client;

import com.mojang.logging.LogUtils;
import io.github.artmasster.figuralocal.core.ClientSide;
import io.github.artmasster.figuralocal.forge.Network;
import io.github.artmasster.figuralocal.forge.TunnelPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.network.NetworkDirection;
import org.slf4j.Logger;

import java.lang.reflect.Method;

/** Player side: starts the local proxy when the server says hello and stops it on logout. */
public final class ClientHooks {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * The connection the server's frames arrive on. Frames are sent back on it directly, because
     * the hello can arrive before the game has created the player, when the usual
     * "send to server" helper has no connection yet.
     */
    private static volatile Connection connection;

    private static final ClientSide CLIENT = new ClientSide(frame -> {
        Connection target = connection;
        if (target == null)
            return;
        try {
            Network.CHANNEL.sendTo(new TunnelPacket(frame), target, NetworkDirection.PLAY_TO_SERVER);
        } catch (RuntimeException e) {
            // Usually means the connection is already gone.
            LOGGER.debug("Could not send a tunnel frame to the server", e);
        }
    });

    private ClientHooks() {
    }

    public static void register() {
        MinecraftForge.EVENT_BUS.addListener(ClientHooks::onLogout);
    }

    public static void frameFromServer(byte[] frame, Connection from) {
        connection = from;
        try {
            if (CLIENT.frameFromServer(frame)) {
                LOGGER.info("This server stores Figura avatars itself; switching Figura to it");
                reconnectFigura();
            }
        } catch (Exception e) {
            LOGGER.error("FiguraLocal could not start its local proxy; staying on the Figura cloud", e);
            CLIENT.stop();
        }
    }

    private static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        connection = null;
        if (!CLIENT.active())
            return;
        CLIENT.stop();
        LOGGER.info("Left the FiguraLocal server; switching Figura back to its cloud");
        reconnectFigura();
    }

    /**
     * Asks Figura to log in again, which makes it read the backend address anew.
     * Reflection keeps this mod free of a compile-time dependency on Figura.
     */
    private static void reconnectFigura() {
        Minecraft.getInstance().execute(() -> {
            try {
                Method reAuth = Class.forName("org.figuramc.figura.backend2.NetworkStuff").getMethod("reAuth");
                reAuth.invoke(null);
            } catch (ClassNotFoundException e) {
                LOGGER.info("Figura is not installed, so FiguraLocal has nothing to do on this client");
            } catch (ReflectiveOperationException | RuntimeException e) {
                LOGGER.error("FiguraLocal could not ask Figura to reconnect", e);
            }
        });
    }
}
