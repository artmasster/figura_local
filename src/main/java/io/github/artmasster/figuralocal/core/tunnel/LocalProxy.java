package io.github.artmasster.figuralocal.core.tunnel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Player side of the tunnel: listens on a loopback port and forwards every connection it accepts
 * through the {@link TunnelMux}. Figura is pointed at this port, so its own HTTP and WebSocket
 * code works unchanged while the bytes travel over the game connection.
 */
public final class LocalProxy implements Closeable {

    private static final Logger LOGGER = LoggerFactory.getLogger("FiguraLocal");
    private static final AtomicInteger THREADS = new AtomicInteger();

    private final TunnelMux mux;
    private final ServerSocket server;
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "FiguraLocal-proxy-" + THREADS.incrementAndGet());
        t.setDaemon(true);
        return t;
    });

    public LocalProxy(TunnelMux mux) throws IOException {
        this.mux = mux;
        // Bind the IPv4 loopback explicitly. InetAddress.getLoopbackAddress() can be ::1, and
        // Figura is told to use 127.0.0.1.
        this.server = new ServerSocket(0, 50, InetAddress.getByAddress(new byte[]{127, 0, 0, 1}));
        pool.execute(this::acceptLoop);
    }

    public int port() {
        return server.getLocalPort();
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            Socket socket;
            try {
                socket = server.accept();
            } catch (IOException e) {
                return;
            }
            TunnelStream stream;
            try {
                socket.setTcpNoDelay(true);
                stream = mux.open();
            } catch (Exception e) {
                LOGGER.debug("Could not open a tunnel stream", e);
                closeQuietly(socket);
                continue;
            }
            LOGGER.debug("Proxy connection {} opened", stream.id());
            sockets.add(socket);
            pool.execute(() -> pump(socket, stream, true));
            pool.execute(() -> pump(socket, stream, false));
        }
    }

    private void pump(Socket socket, TunnelStream stream, boolean upstream) {
        byte[] buf = new byte[16384];
        try {
            InputStream from = upstream ? socket.getInputStream() : stream.in();
            OutputStream to = upstream ? stream.out() : socket.getOutputStream();
            int n;
            while ((n = from.read(buf)) >= 0) {
                to.write(buf, 0, n);
                to.flush();
            }
        } catch (IOException e) {
            // Either end went away; both are closed below.
            LOGGER.debug("Proxy connection {} ended ({}): {}", stream.id(), upstream ? "to server" : "to Figura", e.toString());
        } finally {
            stream.close();
            closeQuietly(socket);
            sockets.remove(socket);
        }
    }

    @Override
    public void close() {
        closeQuietly(server);
        mux.close();
        for (Socket socket : sockets)
            closeQuietly(socket);
        pool.shutdownNow();
    }

    private static void closeQuietly(Closeable c) {
        try {
            c.close();
        } catch (IOException ignored) {
        }
    }
}
