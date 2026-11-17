package io.github.artmasster.figuralocal.core;

/**
 * Where Figura should send its backend traffic while the player is on a FiguraLocal server.
 * Both values are null when no override is active, and Figura then uses its normal cloud.
 */
public final class BackendOverride {

    private static volatile String httpBase;
    private static volatile String wsBase;

    private BackendOverride() {
    }

    /** Sets the override, e.g. {@code http://127.0.0.1:50123/secret/api} and {@code ws://127.0.0.1:50123/secret/ws}. */
    public static void set(String http, String ws) {
        httpBase = http;
        wsBase = ws;
    }

    public static void clear() {
        httpBase = null;
        wsBase = null;
    }

    public static boolean active() {
        return httpBase != null;
    }

    public static String httpBase() {
        return httpBase;
    }

    public static String wsBase() {
        return wsBase;
    }
}
