package io.github.artmasster.figuralocal.core.tunnel;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;

/** One byte stream inside a {@link TunnelMux}. Reads and writes block like a socket. */
public final class TunnelStream {

    private final TunnelMux mux;
    private final int id;

    // inbound
    private final Object inLock = new Object();
    private final ArrayDeque<byte[]> inQueue = new ArrayDeque<>();
    private int headOffset;
    private int queuedBytes;
    private int consumedSinceAck;
    private boolean inEof;

    // outbound
    private final Object outLock = new Object();
    private final Object writeLock = new Object();
    private long unacked;
    private boolean outClosed;

    private boolean remoteClosed;
    private boolean localClosed;

    private final InputStream in = new In();
    private final OutputStream out = new Out();

    TunnelStream(TunnelMux mux, int id) {
        this.mux = mux;
        this.id = id;
    }

    public int id() {
        return id;
    }

    public InputStream in() {
        return in;
    }

    public OutputStream out() {
        return out;
    }

    /** Closes the stream on both sides. Safe to call more than once. */
    public void close() {
        boolean notify;
        synchronized (this) {
            if (localClosed)
                return;
            localClosed = true;
            notify = !remoteClosed;
        }
        mux.forget(this);
        if (notify)
            mux.send(Frames.control(Frames.CLOSE, id));
        shutdown(false);
    }

    void onData(byte[] data) {
        if (data.length == 0)
            return;
        boolean overflow;
        synchronized (inLock) {
            if (inEof)
                return;
            inQueue.add(data);
            queuedBytes += data.length;
            // A well-behaved sender never has more than one window in flight.
            overflow = queuedBytes > mux.window() * 2 + Frames.MAX_CHUNK;
            inLock.notifyAll();
        }
        if (overflow)
            close();
    }

    void onAck(int bytes) {
        synchronized (outLock) {
            unacked = Math.max(0, unacked - Math.max(0, bytes));
            outLock.notifyAll();
        }
    }

    void onRemoteClose() {
        synchronized (this) {
            remoteClosed = true;
        }
        shutdown(true);
    }

    /** @param drain keep already received bytes readable, so the reader sees them before end of stream */
    private void shutdown(boolean drain) {
        synchronized (inLock) {
            inEof = true;
            if (!drain) {
                inQueue.clear();
                queuedBytes = 0;
            }
            inLock.notifyAll();
        }
        synchronized (outLock) {
            outClosed = true;
            outLock.notifyAll();
        }
    }

    private final class In extends InputStream {
        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0)
                return 0;
            int copied;
            int ack = 0;
            synchronized (inLock) {
                while (inQueue.isEmpty() && !inEof) {
                    try {
                        inLock.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("interrupted");
                    }
                }
                if (inQueue.isEmpty())
                    return -1;
                byte[] head = inQueue.peek();
                copied = Math.min(len, head.length - headOffset);
                System.arraycopy(head, headOffset, b, off, copied);
                headOffset += copied;
                if (headOffset == head.length) {
                    inQueue.poll();
                    headOffset = 0;
                }
                queuedBytes -= copied;
                consumedSinceAck += copied;
                if (consumedSinceAck >= mux.window() / 4 || queuedBytes == 0) {
                    ack = consumedSinceAck;
                    consumedSinceAck = 0;
                }
            }
            if (ack > 0)
                mux.send(Frames.ack(id, ack));
            return copied;
        }

        @Override
        public int available() {
            synchronized (inLock) {
                return queuedBytes;
            }
        }

        @Override
        public void close() {
            TunnelStream.this.close();
        }
    }

    private final class Out extends OutputStream {
        @Override
        public void write(int b) throws IOException {
            write(new byte[]{(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            int maxChunk = Math.min(Frames.MAX_CHUNK, mux.window());
            synchronized (writeLock) {
                while (len > 0) {
                    int chunk = Math.min(len, maxChunk);
                    synchronized (outLock) {
                        while (!outClosed && unacked + chunk > mux.window()) {
                            try {
                                outLock.wait();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new IOException("interrupted");
                            }
                        }
                        if (outClosed)
                            throw new IOException("stream closed");
                        unacked += chunk;
                    }
                    mux.send(Frames.data(id, b, off, chunk));
                    off += chunk;
                    len -= chunk;
                }
            }
        }

        @Override
        public void close() {
            TunnelStream.this.close();
        }
    }
}
