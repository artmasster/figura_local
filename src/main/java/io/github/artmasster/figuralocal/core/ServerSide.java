package io.github.artmasster.figuralocal.core;

import io.github.artmasster.figuralocal.core.backend.BackendConfig;
import io.github.artmasster.figuralocal.core.backend.FiguraBackend;
import io.github.artmasster.figuralocal.core.backend.Identity;
import io.github.artmasster.figuralocal.core.tunnel.Frames;
import io.github.artmasster.figuralocal.core.tunnel.TunnelMux;
import io.github.artmasster.figuralocal.core.tunnel.TunnelStream;

import java.io.IOException;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Everything the Minecraft server runs: the avatar backend and one tunnel per player.
 * It has no Minecraft code in it; the mod loader glue only passes players and packets in.
 */
public final class ServerSide {

    private static final int MAX_STREAMS_PER_PLAYER = 32;
    private static final AtomicInteger THREADS = new AtomicInteger();

    private final BackendConfig config;
    private final FiguraBackend backend;
    private static final Logger LOGGER = LoggerFactory.getLogger("FiguraLocal");

    private final Map<UUID, TunnelMux> tunnels = new ConcurrentHashMap<>();
    private final Set<UUID> connected = ConcurrentHashMap.newKeySet();
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "FiguraLocal-backend-" + THREADS.incrementAndGet());
        t.setDaemon(true);
        return t;
    });

    /**
     * @param dataDir    folder for avatars, created when missing
     * @param configFile settings file, created with defaults when missing
     */
    public ServerSide(Path dataDir, Path configFile) throws IOException {
        this.config = BackendConfig.load(configFile);
        this.backend = new FiguraBackend(dataDir, config);
    }

    public BackendConfig config() {
        return config;
    }

    /**
     * Sets up the tunnel of a player who just joined and sends them the hello frame.
     *
     * @param sender sends one frame to this player; must be safe to call from any thread
     */
    public void playerJoined(Identity player, TunnelMux.FrameSender sender) {
        if (!config.enabled)
            return;
        String secret = backend.newSecret();
        TunnelMux mux = new TunnelMux(sender, stream -> serve(stream, player, secret),
                config.windowBytes(), MAX_STREAMS_PER_PLAYER);
        TunnelMux old = tunnels.put(player.uuid(), mux);
        if (old != null)
            old.close();
        sender.send(Frames.helloFromServer(secret, config.windowBytes()));
    }

    private void serve(TunnelStream stream, Identity player, String secret) {
        if (connected.add(player.uuid()))
            LOGGER.info("{} is now using this server for Figura avatars", player.name());
        try {
            pool.execute(() -> {
                try {
                    backend.serve(stream.in(), stream.out(), player, secret);
                } finally {
                    stream.close();
                }
            });
        } catch (RuntimeException e) {
            // Pool already shut down.
            stream.close();
        }
    }

    /** Handles one frame a player sent. Frames from players without a tunnel are ignored. */
    public void frameFrom(UUID player, byte[] frame) {
        TunnelMux mux = tunnels.get(player);
        if (mux == null || frame.length == 0 || Frames.isHello(frame))
            return;
        mux.onFrame(frame);
    }

    public void playerLeft(UUID player) {
        connected.remove(player);
        TunnelMux mux = tunnels.remove(player);
        if (mux != null)
            mux.close();
        backend.playerLeft(player);
    }

    public void stop() {
        for (TunnelMux mux : tunnels.values())
            mux.close();
        tunnels.clear();
        backend.close();
        pool.shutdownNow();
    }
}
