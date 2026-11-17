package io.github.artmasster.figuralocal.core.backend;

import io.github.artmasster.figuralocal.core.http.HttpConnection;
import io.github.artmasster.figuralocal.core.http.HttpRequest;
import io.github.artmasster.figuralocal.core.http.HttpResponse;
import io.github.artmasster.figuralocal.core.http.WsConnection;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An avatar backend that answers the same HTTP and WebSocket calls as Figura's cloud, so an
 * unmodified Figura client can talk to it.
 *
 * <p>Every connection arrives through the tunnel of one player, so the backend already knows who
 * is on the other end. A token is still issued, because the Figura client expects that flow, but
 * a token only works on connections of the player it was issued to.
 */
public final class FiguraBackend {

    private static final long AUTH_TTL_MS = 60_000;
    private static final int MAX_PENDING_PER_PLAYER = 8;
    private static final String NO_BADGES =
            "{\"special\":[0,0,0,0,0,0],\"pride\":[0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0]}";

    private final BackendConfig config;
    private final AvatarStore store;
    private final WsHub hub;
    private final SecureRandom random = new SecureRandom();

    private final Map<String, Identity> tokens = new ConcurrentHashMap<>();
    private final Map<UUID, String> tokenOf = new ConcurrentHashMap<>();
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    private record Pending(UUID player, long expires) {
    }

    public FiguraBackend(Path dataDir, BackendConfig config) throws IOException {
        this.config = config;
        this.store = new AvatarStore(dataDir.resolve("avatars"));
        this.hub = new WsHub(config, tokens::get);
    }

    /** Creates the secret a player's client must put in front of every path. */
    public String newSecret() {
        return randomHex(16);
    }

    /**
     * Serves one connection of a player until it closes. Blocks, so call it on its own thread.
     *
     * @param secret the secret that was sent to this player in the tunnel handshake
     */
    public void serve(InputStream in, OutputStream out, Identity player, String secret) {
        // Pings are checked against pingSize later, so that the player gets a notice instead of a dropped connection.
        int maxWsMessage = Math.max(65536, config.pingSize) + 64;
        new HttpConnection(in, out, new Handler(player, secret), config.maxAvatarBytes(), maxWsMessage).serve();
    }

    /** Forgets the login of a player who left the server. */
    public void playerLeft(UUID player) {
        String token = tokenOf.remove(player);
        if (token != null)
            tokens.remove(token);
        pending.values().removeIf(p -> p.player().equals(player));
    }

    public void close() {
        hub.close();
        tokens.clear();
        tokenOf.clear();
        pending.clear();
    }

    private String randomHex(int bytes) {
        byte[] buf = new byte[bytes];
        random.nextBytes(buf);
        return HexFormat.of().formatHex(buf);
    }

    private final class Handler implements HttpConnection.Handler {
        private final Identity player;
        private final byte[] secret;

        Handler(Identity player, String secret) {
            this.player = player;
            this.secret = secret.getBytes(StandardCharsets.UTF_8);
        }

        private boolean secretMatches(List<String> segments) {
            return !segments.isEmpty()
                    && MessageDigest.isEqual(segments.get(0).getBytes(StandardCharsets.UTF_8), secret);
        }

        @Override
        public WsConnection.Listener upgrade(HttpRequest request) {
            List<String> segments = request.segments();
            if (!secretMatches(segments) || segments.size() != 2 || !segments.get(1).equals("ws"))
                return null;
            return hub.newClient(player);
        }

        @Override
        public HttpResponse handle(HttpRequest request) throws IOException {
            List<String> segments = request.segments();
            if (!secretMatches(segments))
                return HttpResponse.text(403, "Forbidden");
            if (segments.size() < 2 || !segments.get(1).equals("api"))
                return HttpResponse.text(404, "Not found");
            List<String> path = segments.subList(2, segments.size());

            return switch (request.method) {
                case "GET" -> get(request, path);
                case "PUT" -> path.size() == 1 ? upload(request) : HttpResponse.text(404, "Not found");
                case "DELETE" -> path.size() == 1 ? delete(request) : HttpResponse.text(404, "Not found");
                case "POST" -> post(request, path);
                default -> HttpResponse.text(405, "Method not allowed");
            };
        }

        private boolean loggedIn(HttpRequest request) {
            String token = request.header("token");
            Identity owner = token == null ? null : tokens.get(token);
            return owner != null && owner.uuid().equals(player.uuid());
        }

        private HttpResponse get(HttpRequest request, List<String> path) throws IOException {
            if (path.isEmpty())
                return loggedIn(request) ? HttpResponse.text(200, "ok") : unauthorized();

            String first = path.get(0);
            switch (first) {
                case "auth" -> {
                    if (path.size() == 2 && path.get(1).equals("id"))
                        return authId();
                    if (path.size() == 2 && path.get(1).equals("verify"))
                        return authVerify(request.query("id"));
                    return HttpResponse.text(404, "Not found");
                }
                case "limits" -> {
                    return HttpResponse.json(200, limits());
                }
                case "version" -> {
                    // Figura compares this with its own version to offer updates; 0.0.0 never does.
                    return HttpResponse.json(200, "{\"release\":\"0.0.0\",\"prerelease\":\"0.0.0\"}");
                }
                case "motd" -> {
                    return HttpResponse.text(200, config.motd.isEmpty() ? "" : "{\"text\":" + quote(config.motd) + "}");
                }
                default -> {
                }
            }

            UUID owner = parseUuid(first);
            if (owner == null)
                return HttpResponse.text(404, "Not found");
            if (!loggedIn(request))
                return unauthorized();
            if (path.size() == 1)
                return HttpResponse.json(200, user(owner));
            if (path.size() == 2) {
                byte[] avatar = store.get(owner);
                return avatar == null ? HttpResponse.text(404, "Avatar not found") : HttpResponse.bytes(200, avatar);
            }
            return HttpResponse.text(404, "Not found");
        }

        private HttpResponse authId() {
            long now = System.currentTimeMillis();
            pending.values().removeIf(p -> p.expires() < now);
            long mine = pending.values().stream().filter(p -> p.player().equals(player.uuid())).count();
            if (mine >= MAX_PENDING_PER_PLAYER)
                return HttpResponse.text(429, "Too many login attempts");
            String id = randomHex(16);
            pending.put(id, new Pending(player.uuid(), now + AUTH_TTL_MS));
            return HttpResponse.text(200, id);
        }

        private HttpResponse authVerify(String id) {
            Pending request = id == null ? null : pending.remove(id);
            if (request == null || !request.player().equals(player.uuid()) || request.expires() < System.currentTimeMillis())
                return HttpResponse.text(404, "Failed to authenticate");
            String token = randomHex(16);
            String old = tokenOf.put(player.uuid(), token);
            if (old != null)
                tokens.remove(old);
            tokens.put(token, player);
            return HttpResponse.text(200, token);
        }

        private HttpResponse upload(HttpRequest request) throws IOException {
            if (!loggedIn(request))
                return unauthorized();
            if (request.body.length == 0)
                return HttpResponse.text(400, "Empty avatar");
            if (request.body.length > config.maxAvatarBytes())
                return HttpResponse.text(413, "Avatar too large");
            store.put(player.uuid(), request.body);
            return HttpResponse.text(200, "ok");
        }

        private HttpResponse delete(HttpRequest request) throws IOException {
            if (!loggedIn(request))
                return unauthorized();
            if (!store.delete(player.uuid()))
                return HttpResponse.text(404, "Avatar not found");
            hub.avatarChanged(player.uuid());
            return HttpResponse.text(200, "ok");
        }

        private HttpResponse post(HttpRequest request, List<String> path) {
            if (path.size() != 1)
                return HttpResponse.text(404, "Not found");
            if (!loggedIn(request))
                return unauthorized();
            switch (path.get(0)) {
                case "equip" -> {
                    hub.avatarChanged(player.uuid());
                    return HttpResponse.text(200, "ok");
                }
                case "temp_badges" -> {
                    return HttpResponse.text(200, "ok");
                }
                default -> {
                    return HttpResponse.text(404, "Not found");
                }
            }
        }

        private HttpResponse unauthorized() {
            return HttpResponse.text(401, "Unauthorized");
        }

        private String limits() {
            return "{\"rate\":{\"pingSize\":" + config.pingSize + ",\"pingRate\":" + config.pingRate
                    + ",\"equip\":1,\"download\":50,\"upload\":1},"
                    + "\"limits\":{\"maxAvatarSize\":" + config.maxAvatarBytes() + ",\"maxAvatars\":1,"
                    + "\"allowedBadges\":" + NO_BADGES + "}}";
        }

        private String user(UUID owner) throws IOException {
            String hash = store.hash(owner);
            String equipped = hash == null ? ""
                    : "{\"id\":\"avatar\",\"owner\":\"" + owner + "\",\"hash\":\"" + hash + "\"}";
            return "{\"uuid\":\"" + owner + "\",\"rank\":\"default\",\"equipped\":[" + equipped + "],"
                    + "\"lastUsed\":\"" + Instant.now() + "\",\"equippedBadges\":" + NO_BADGES + ","
                    + "\"version\":\"0.1.5\",\"banned\":false}";
        }
    }

    private static UUID parseUuid(String text) {
        if (text.length() != 36)
            return null;
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String quote(String text) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20)
                        sb.append(String.format("\\u%04x", (int) c));
                    else
                        sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }
}
