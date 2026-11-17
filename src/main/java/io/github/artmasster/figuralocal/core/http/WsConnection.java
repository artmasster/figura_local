package io.github.artmasster.figuralocal.core.http;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Server side of one WebSocket connection (RFC 6455) without extensions.
 * Only binary messages are delivered to the listener; Figura does not use text messages.
 */
public final class WsConnection {

    public interface Listener {
        void onOpen(WsConnection connection);

        void onBinary(WsConnection connection, byte[] message);

        void onClose(WsConnection connection);
    }

    private static final int OP_CONTINUATION = 0, OP_TEXT = 1, OP_BINARY = 2, OP_CLOSE = 8, OP_PING = 9, OP_PONG = 10;

    private final DataInputStream in;
    private final OutputStream out;
    private final Listener listener;
    private final int maxMessage;
    private boolean closeSent;

    WsConnection(InputStream in, OutputStream out, Listener listener, int maxMessage) {
        this.in = new DataInputStream(in);
        this.out = out;
        this.listener = listener;
        this.maxMessage = maxMessage;
    }

    /** Reads frames until the connection ends. */
    void run() {
        listener.onOpen(this);
        try {
            readLoop();
        } catch (IOException ignored) {
            // Connection dropped.
        } finally {
            listener.onClose(this);
        }
    }

    private void readLoop() throws IOException {
        ByteArrayOutputStream message = null;
        int messageOp = 0;
        while (true) {
            int b0 = in.read();
            if (b0 < 0)
                return;
            int b1 = in.readUnsignedByte();
            boolean fin = (b0 & 0x80) != 0;
            int op = b0 & 0x0F;
            boolean masked = (b1 & 0x80) != 0;
            long len = b1 & 0x7F;
            if (len == 126)
                len = in.readUnsignedShort();
            else if (len == 127)
                len = in.readLong();

            // No extension was negotiated, so reserved bits must be clear, and clients always mask.
            if ((b0 & 0x70) != 0 || !masked) {
                close(1002, "Protocol Error");
                return;
            }
            if (len < 0 || len > maxMessage) {
                close(1009, "Message Too Big");
                return;
            }
            byte[] mask = new byte[4];
            in.readFully(mask);
            byte[] payload = new byte[(int) len];
            in.readFully(payload);
            for (int i = 0; i < payload.length; i++)
                payload[i] ^= mask[i & 3];

            switch (op) {
                case OP_PING -> writeFrame(OP_PONG, payload);
                case OP_PONG -> {
                }
                case OP_CLOSE -> {
                    close(1000, "");
                    return;
                }
                case OP_TEXT, OP_BINARY -> {
                    if (message != null) {
                        close(1002, "Protocol Error");
                        return;
                    }
                    if (fin) {
                        if (op == OP_BINARY)
                            listener.onBinary(this, payload);
                    } else {
                        message = new ByteArrayOutputStream();
                        message.write(payload);
                        messageOp = op;
                    }
                }
                case OP_CONTINUATION -> {
                    if (message == null) {
                        close(1002, "Protocol Error");
                        return;
                    }
                    if (message.size() + payload.length > maxMessage) {
                        close(1009, "Message Too Big");
                        return;
                    }
                    message.write(payload);
                    if (fin) {
                        if (messageOp == OP_BINARY)
                            listener.onBinary(this, message.toByteArray());
                        message = null;
                    }
                }
                default -> {
                    close(1002, "Protocol Error");
                    return;
                }
            }
        }
    }

    public void sendBinary(byte[] message) throws IOException {
        writeFrame(OP_BINARY, message);
    }

    /** Sends a close frame. The other side answers and the read loop ends. */
    public void close(int code, String reason) {
        byte[] text = reason.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[2 + text.length];
        payload[0] = (byte) (code >> 8);
        payload[1] = (byte) code;
        System.arraycopy(text, 0, payload, 2, text.length);
        try {
            synchronized (this) {
                if (closeSent)
                    return;
                closeSent = true;
            }
            writeFrameUnchecked(OP_CLOSE, payload);
        } catch (IOException ignored) {
        }
    }

    private void writeFrame(int op, byte[] payload) throws IOException {
        synchronized (this) {
            if (closeSent)
                throw new EOFException("websocket closed");
        }
        writeFrameUnchecked(op, payload);
    }

    private synchronized void writeFrameUnchecked(int op, byte[] payload) throws IOException {
        int len = payload.length;
        ByteArrayOutputStream frame = new ByteArrayOutputStream(len + 10);
        frame.write(0x80 | op);
        if (len < 126) {
            frame.write(len);
        } else if (len < 65536) {
            frame.write(126);
            frame.write(len >> 8);
            frame.write(len);
        } else {
            frame.write(127);
            for (int shift = 56; shift >= 0; shift -= 8)
                frame.write((int) ((long) len >> shift));
        }
        frame.write(payload);
        out.write(frame.toByteArray());
        out.flush();
    }
}
