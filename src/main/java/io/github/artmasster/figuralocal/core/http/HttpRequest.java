package io.github.artmasster.figuralocal.core.http;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** One parsed HTTP request. Header names are lower case. */
public final class HttpRequest {

    public final String method;
    public final String target;
    public final Map<String, String> headers;
    public final byte[] body;

    private final List<String> segments = new ArrayList<>();
    private final String query;

    HttpRequest(String method, String target, Map<String, String> headers, byte[] body) {
        this.method = method;
        this.target = target;
        this.headers = headers;
        this.body = body;

        int q = target.indexOf('?');
        String path = q < 0 ? target : target.substring(0, q);
        this.query = q < 0 ? "" : target.substring(q + 1);
        // Figura builds URLs such as "/api//auth/id", so empty segments are dropped.
        for (String part : path.split("/")) {
            if (!part.isEmpty())
                segments.add(decode(part));
        }
    }

    /** Path segments, decoded, without empty ones. */
    public List<String> segments() {
        return segments;
    }

    public String header(String name) {
        return headers.get(name);
    }

    /** First value of a query parameter, or null. */
    public String query(String name) {
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            if (decode(key).equals(name))
                return eq < 0 ? "" : decode(pair.substring(eq + 1));
        }
        return null;
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return s;
        }
    }
}
