package io.github.artmasster.figuralocal.core.tunnel;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Wire format of the tunnel. Every Minecraft custom payload packet carries exactly one frame:
 * one type byte followed by the fields of that type.
 */
public final class Frames {

    public static final int PROTOCOL = 1;

    /** Server to client: protocol, window, secret. Client to server: protocol. */
    public static final byte HELLO = 0;
    /** Opener to peer: stream id. */
    public static final byte OPEN = 1;
    /** Either way: stream id, bytes. */
    public static final byte DATA = 2;
    /** Either way: stream id. */
    public static final byte CLOSE = 3;
    /** Receiver to sender: stream id, number of bytes the receiver has consumed. */
    public static final byte ACK = 4;

    /** Largest data chunk in one frame. Client-to-server custom payloads are capped at 32767 bytes. */
    public static final int MAX_CHUNK = 30000;

    private Frames() {
    }

    public static byte[] helloFromServer(String secret, int window) {
        byte[] s = secret.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(6 + s.length).put(HELLO).put((byte) PROTOCOL).putInt(window).put(s).array();
    }

    public static byte[] helloFromClient() {
        return new byte[]{HELLO, (byte) PROTOCOL};
    }

    public static boolean isHello(byte[] frame) {
        return frame.length >= 2 && frame[0] == HELLO;
    }

    public static int helloProtocol(byte[] frame) {
        return frame[1] & 0xFF;
    }

    /** True for a server hello that carries the window and a secret. */
    public static boolean isServerHello(byte[] frame) {
        return isHello(frame) && frame.length > 6;
    }

    public static int helloWindow(byte[] frame) {
        return ByteBuffer.wrap(frame, 2, 4).getInt();
    }

    public static String helloSecret(byte[] frame) {
        return new String(frame, 6, frame.length - 6, StandardCharsets.UTF_8);
    }

    static byte[] control(byte type, int stream) {
        return ByteBuffer.allocate(5).put(type).putInt(stream).array();
    }

    static byte[] ack(int stream, int bytes) {
        return ByteBuffer.allocate(9).put(ACK).putInt(stream).putInt(bytes).array();
    }

    static byte[] data(int stream, byte[] src, int off, int len) {
        return ByteBuffer.allocate(5 + len).put(DATA).putInt(stream).put(src, off, len).array();
    }
}
