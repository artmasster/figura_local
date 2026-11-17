package io.github.artmasster.figuralocal.core.http;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A small HTTP/1.1 server for one connection, working on plain streams instead of a socket.
 * It handles keep-alive requests with a Content-Length body and the WebSocket upgrade.
 * That is all the Figura client needs, and it lets the same code run over the game tunnel.
 */
public final class HttpConnection {

    public interface Handler {
        HttpResponse handle(HttpRequest request) throws Exception;

        /** Returns a listener to accept a WebSocket upgrade for this request, or null to refuse it. */
        WsConnection.Listener upgrade(HttpRequest request);
    }

    private static final int MAX_LINE = 8192;
    private static final int MAX_HEADERS = 64;
    private static final long MAX_DISCARD = 64L * 1024 * 1024;
    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private final InputStream in;
    private final OutputStream out;
    private final Handler handler;
    private final int maxBody;
    private final int maxWsMessage;

    public HttpConnection(InputStream in, OutputStream out, Handler handler, int maxBody, int maxWsMessage) {
        this.in = new BufferedInputStream(in, 16384);
        this.out = out;
        this.handler = handler;
        this.maxBody = maxBody;
        this.maxWsMessage = maxWsMessage;
    }

    /** Serves requests until the other side closes the connection. Never throws. */
    public void serve() {
        try {
            while (serveOne()) {
                // next request on the same connection
            }
        } catch (IOException ignored) {
            // Connection dropped.
        }
    }

    private boolean serveOne() throws IOException {
        String requestLine = readLine();
        if (requestLine == null)
            return false;
        if (requestLine.isEmpty())
            return true; // stray line break between requests

        String[] parts = requestLine.split(" ");
        if (parts.length != 3 || !parts[2].startsWith("HTTP/1.")) {
            write(HttpResponse.text(400, "Bad request"), false);
            return false;
        }

        Map<String, String> headers = new HashMap<>();
        while (true) {
            String line = readLine();
            if (line == null)
                return false;
            if (line.isEmpty())
                break;
            if (headers.size() >= MAX_HEADERS) {
                write(HttpResponse.text(431, "Too many headers"), false);
                return false;
            }
            int colon = line.indexOf(':');
            if (colon > 0)
                headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
        }

        if (headers.containsKey("transfer-encoding")) {
            write(HttpResponse.text(411, "Content-Length required"), false);
            return false;
        }

        int length = 0;
        String contentLength = headers.get("content-length");
        if (contentLength != null) {
            try {
                length = Integer.parseInt(contentLength);
            } catch (NumberFormatException e) {
                length = -1;
            }
            if (length < 0) {
                write(HttpResponse.text(400, "Bad Content-Length"), false);
                return false;
            }
            if (length > maxBody) {
                // Read and drop a moderately oversized body first, so the client receives the
                // answer instead of a broken connection while it is still sending.
                if (length <= Math.min(MAX_DISCARD, maxBody * 4L)) {
                    try {
                        in.skipNBytes(length);
                    } catch (java.io.EOFException e) {
                        return false;
                    }
                }
                write(HttpResponse.text(413, "Too large"), false);
                return false;
            }
        }
        if ("100-continue".equalsIgnoreCase(headers.get("expect")))
            writeRaw("HTTP/1.1 100 Continue\r\n\r\n");

        byte[] body = in.readNBytes(length);
        if (body.length != length)
            return false;

        HttpRequest request = new HttpRequest(parts[0], parts[1], headers, body);

        String upgrade = headers.getOrDefault("upgrade", "");
        if (upgrade.toLowerCase(Locale.ROOT).contains("websocket")) {
            String key = headers.get("sec-websocket-key");
            WsConnection.Listener listener = key == null ? null : handler.upgrade(request);
            if (listener == null) {
                write(HttpResponse.text(403, "Forbidden"), false);
                return false;
            }
            // No Sec-WebSocket-Extensions header: the offered permessage-deflate is declined.
            writeRaw("HTTP/1.1 101 Switching Protocols\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + acceptKey(key) + "\r\n\r\n");
            new WsConnection(in, out, listener, maxWsMessage).run();
            return false;
        }

        HttpResponse response;
        try {
            response = handler.handle(request);
        } catch (Exception e) {
            response = HttpResponse.text(500, "Internal error");
        }
        boolean keepAlive = !"close".equalsIgnoreCase(headers.get("connection")) && parts[2].equals("HTTP/1.1");
        write(response, keepAlive);
        return keepAlive;
    }

    private void write(HttpResponse response, boolean keepAlive) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(response.body.length + 256);
        String head = "HTTP/1.1 " + response.status + " " + HttpResponse.reason(response.status) + "\r\n"
                + "Content-Type: " + response.contentType + "\r\n"
                + "Content-Length: " + response.body.length + "\r\n"
                + "Connection: " + (keepAlive ? "keep-alive" : "close") + "\r\n\r\n";
        buffer.write(head.getBytes(StandardCharsets.ISO_8859_1));
        buffer.write(response.body);
        out.write(buffer.toByteArray());
        out.flush();
    }

    private void writeRaw(String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    /** Reads one line without its line break, or null at end of stream or when the line is too long. */
    private String readLine() throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream(128);
        while (true) {
            int b = in.read();
            if (b < 0)
                return null;
            if (b == '\n')
                break;
            if (b != '\r')
                line.write(b);
            if (line.size() > MAX_LINE)
                return null;
        }
        return line.toString(StandardCharsets.ISO_8859_1);
    }

    static String acceptKey(String key) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            return Base64.getEncoder().encodeToString(sha1.digest((key + WS_GUID).getBytes(StandardCharsets.ISO_8859_1)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
