package io.github.artmasster.figuralocal.core;

import io.github.artmasster.figuralocal.core.tunnel.Frames;
import io.github.artmasster.figuralocal.core.tunnel.LocalProxy;
import io.github.artmasster.figuralocal.core.tunnel.TunnelMux;

import java.io.IOException;

/**
 * Everything the player's game runs while connected to a FiguraLocal server: a loopback proxy
 * whose connections travel through the tunnel, and the address override that points Figura at it.
 * It has no Minecraft code in it.
 */
public final class ClientSide {

    private static final int MIN_WINDOW = 8 * 1024;
    private static final int MAX_WINDOW = 4096 * 1024;

    private final TunnelMux.FrameSender sender;
    private TunnelMux mux;
    private LocalProxy proxy;
    private String httpBase;
    private String wsBase;

    /** @param sender sends one frame to the server; must be safe to call from any thread */
    public ClientSide(TunnelMux.FrameSender sender) {
        this.sender = sender;
    }

    /**
     * Handles one frame from the server.
     *
     * @return true when this frame was the server's hello and the override has just become active
     */
    public synchronized boolean frameFromServer(byte[] frame) throws IOException {
        if (frame.length == 0)
            return false;
        if (Frames.isHello(frame)) {
            if (!Frames.isServerHello(frame) || Frames.helloProtocol(frame) != Frames.PROTOCOL)
                return false;
            stop();
            int window = Math.max(MIN_WINDOW, Math.min(MAX_WINDOW, Frames.helloWindow(frame)));
            String secret = Frames.helloSecret(frame);
            if (!secret.matches("[0-9a-f]{8,64}"))
                return false;
            mux = new TunnelMux(sender, null, window, 0);
            proxy = new LocalProxy(mux);
            String base = "127.0.0.1:" + proxy.port() + "/" + secret;
            httpBase = "http://" + base + "/api";
            wsBase = "ws://" + base + "/ws";
            BackendOverride.set(httpBase, wsBase);
            sender.send(Frames.helloFromClient());
            return true;
        }
        if (mux != null)
            mux.onFrame(frame);
        return false;
    }

    public synchronized boolean active() {
        return proxy != null;
    }

    /** Address Figura's HTTP calls go to while active, otherwise null. */
    public synchronized String httpBase() {
        return httpBase;
    }

    /** Address Figura's WebSocket goes to while active, otherwise null. */
    public synchronized String wsBase() {
        return wsBase;
    }

    /** Ends the session, for example when the player leaves the server. Safe to call when not active. */
    public synchronized void stop() {
        BackendOverride.clear();
        httpBase = null;
        wsBase = null;
        if (proxy != null) {
            proxy.close();
            proxy = null;
            mux = null;
        }
    }
}
