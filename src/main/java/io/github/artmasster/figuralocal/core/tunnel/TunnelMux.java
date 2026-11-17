package io.github.artmasster.figuralocal.core.tunnel;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Carries several byte streams over one packet channel, such as the game connection of a player.
 * One side opens streams (the player) and the other accepts them (the server).
 *
 * <p>Each stream has a send window: a sender stops after {@code window} unacknowledged bytes and
 * continues when the receiver reports that it consumed them. That keeps a large avatar transfer
 * from piling up in front of the game's own packets.
 */
public final class TunnelMux {

    /** Sends one frame to the other side. Must be safe to call from any thread. */
    public interface FrameSender {
        void send(byte[] frame);
    }

    /** Called on the accepting side for every stream the other side opens. */
    public interface OpenListener {
        void opened(TunnelStream stream);
    }

    private final FrameSender sender;
    private final OpenListener listener;
    private final int window;
    private final int maxStreams;
    private final Map<Integer, TunnelStream> streams = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1);
    private volatile boolean closed;

    /**
     * @param listener   receives streams opened by the other side, or null if this side only opens
     * @param window     unacknowledged bytes allowed per stream
     * @param maxStreams streams the other side may have open at once
     */
    public TunnelMux(FrameSender sender, OpenListener listener, int window, int maxStreams) {
        this.sender = sender;
        this.listener = listener;
        this.window = Math.max(1024, window);
        this.maxStreams = maxStreams;
    }

    int window() {
        return window;
    }

    /** Opens a new stream towards the other side. */
    public TunnelStream open() {
        if (closed)
            throw new IllegalStateException("tunnel closed");
        TunnelStream stream = new TunnelStream(this, nextId.getAndIncrement());
        streams.put(stream.id(), stream);
        send(Frames.control(Frames.OPEN, stream.id()));
        return stream;
    }

    /** Handles one frame from the other side. HELLO frames are not handled here. */
    public void onFrame(byte[] frame) {
        if (closed || frame.length < 5)
            return;
        ByteBuffer buf = ByteBuffer.wrap(frame);
        byte type = buf.get();
        int id = buf.getInt();
        switch (type) {
            case Frames.OPEN -> {
                if (listener == null || streams.size() >= maxStreams || streams.containsKey(id)) {
                    send(Frames.control(Frames.CLOSE, id));
                    return;
                }
                TunnelStream stream = new TunnelStream(this, id);
                streams.put(id, stream);
                listener.opened(stream);
            }
            case Frames.DATA -> {
                TunnelStream stream = streams.get(id);
                if (stream == null)
                    return;
                byte[] data = new byte[buf.remaining()];
                buf.get(data);
                stream.onData(data);
            }
            case Frames.ACK -> {
                TunnelStream stream = streams.get(id);
                if (stream != null && buf.remaining() >= 4)
                    stream.onAck(buf.getInt());
            }
            case Frames.CLOSE -> {
                TunnelStream stream = streams.remove(id);
                if (stream != null)
                    stream.onRemoteClose();
            }
            default -> {
            }
        }
    }

    /** Closes every stream. Threads blocked on a stream fail with an IOException. */
    public void close() {
        closed = true;
        for (TunnelStream stream : streams.values())
            stream.onRemoteClose();
        streams.clear();
    }

    public boolean isClosed() {
        return closed;
    }

    public int openStreams() {
        return streams.size();
    }

    void send(byte[] frame) {
        if (!closed)
            sender.send(frame);
    }

    void forget(TunnelStream stream) {
        streams.remove(stream.id(), stream);
    }
}
