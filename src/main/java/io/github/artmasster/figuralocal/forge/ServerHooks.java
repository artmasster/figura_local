package io.github.artmasster.figuralocal.forge;

import com.mojang.logging.LogUtils;
import io.github.artmasster.figuralocal.core.ServerSide;
import io.github.artmasster.figuralocal.core.backend.Identity;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.network.NetworkDirection;
import org.slf4j.Logger;

import java.nio.file.Path;

/** Starts the backend with the server and gives every joining player a tunnel. */
public final class ServerHooks {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static volatile ServerSide server;

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        Path dataDir = event.getServer().getServerDirectory().toPath().resolve("figuralocal");
        Path configFile = FMLPaths.CONFIGDIR.get().resolve("figuralocal.properties");
        try {
            ServerSide started = new ServerSide(dataDir, configFile);
            server = started;
            if (started.config().enabled)
                LOGGER.info("FiguraLocal is ready. Avatars up to {} KiB are stored in {}",
                        started.config().maxAvatarSizeKb, dataDir.resolve("avatars"));
            else
                LOGGER.info("FiguraLocal is switched off in {}", configFile);
        } catch (Exception e) {
            LOGGER.error("FiguraLocal could not start; players will use the normal Figura cloud", e);
        }
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        ServerSide running = server;
        server = null;
        if (running != null)
            running.stop();
    }

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        ServerSide running = server;
        if (running == null || !(event.getEntity() instanceof ServerPlayer player))
            return;
        // The connection object stays the same when the player respawns, unlike the player object.
        Connection connection = player.connection.connection;
        if (!Network.CHANNEL.isRemotePresent(connection))
            return; // this player does not have FiguraLocal
        Identity identity = new Identity(player.getUUID(), player.getGameProfile().getName());
        running.playerJoined(identity, frame ->
                Network.CHANNEL.sendTo(new TunnelPacket(frame), connection, NetworkDirection.PLAY_TO_CLIENT));
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        ServerSide running = server;
        if (running != null)
            running.playerLeft(event.getEntity().getUUID());
    }

    static void frameFrom(ServerPlayer player, byte[] frame) {
        ServerSide running = server;
        if (running != null)
            running.frameFrom(player.getUUID(), frame);
    }
}
