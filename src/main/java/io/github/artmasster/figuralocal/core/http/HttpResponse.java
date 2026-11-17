package io.github.artmasster.figuralocal.core.http;

import java.nio.charset.StandardCharsets;

/** One HTTP response with a fully buffered body. */
public final class HttpResponse {

    public final int status;
    public final String contentType;
    public final byte[] body;

    public HttpResponse(int status, String contentType, byte[] body) {
        this.status = status;
        this.contentType = contentType;
        this.body = body;
    }

    public static HttpResponse text(int status, String text) {
        return new HttpResponse(status, "text/plain; charset=utf-8", text.getBytes(StandardCharsets.UTF_8));
    }

    public static HttpResponse json(int status, String json) {
        return new HttpResponse(status, "application/json", json.getBytes(StandardCharsets.UTF_8));
    }

    public static HttpResponse bytes(int status, byte[] body) {
        return new HttpResponse(status, "application/octet-stream", body);
    }

    static String reason(int status) {
        return switch (status) {
            case 200 -> "OK";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 411 -> "Length Required";
            case 413 -> "Payload Too Large";
            case 429 -> "Too Many Requests";
            case 431 -> "Request Header Fields Too Large";
            case 500 -> "Internal Server Error";
            default -> "Status";
        };
    }
}
