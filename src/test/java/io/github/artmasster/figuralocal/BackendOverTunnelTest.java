package io.github.artmasster.figuralocal;

import com.neovisionaries.ws.client.WebSocket;
import com.neovisionaries.ws.client.WebSocketAdapter;
import com.neovisionaries.ws.client.WebSocketExtension;
import com.neovisionaries.ws.client.WebSocketFactory;
import io.github.artmasster.figuralocal.core.ClientSide;
import io.github.artmasster.figuralocal.core.ServerSide;
import io.github.artmasster.figuralocal.core.backend.Identity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the server side and one or more player sides in one process, joined by an in-memory
 * "game connection", and talks to the backend with the same HTTP and WebSocket clients Figura uses.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class BackendOverTunnelTest {

    @TempDir
    Path dir;

    private ServerSide server;
    private final List<Player> players = new ArrayList<>();

    /** One simulated player: the client side of the tunnel plus the wires to the server. */
    private final class Player {
        final Identity identity;
        final ClientSide client;
        final ExecutorService toServer = Executors.newSingleThreadExecutor();
        final ExecutorService toClient = Executors.newSingleThreadExecutor();
        final HttpClient http = HttpClient.newHttpClient(); // same default client Figura creates
        String token;

        Player(String name, long delayMillis) throws Exception {
            identity = new Identity(UUID.randomUUID(), name);
            client = new ClientSide(frame -> toServer.execute(() -> {
                sleep(delayMillis);
                server.frameFrom(identity.uuid(), frame);
            }));
            server.playerJoined(identity, frame -> toClient.execute(() -> {
                sleep(delayMillis);
                try {
                    client.frameFromServer(frame);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
            for (int i = 0; i < 200 && !client.active(); i++)
                Thread.sleep(10);
            assertTrue(client.active(), "client did not receive the hello");
            players.add(this);
        }

        HttpResponse<byte[]> send(String method, String path, byte[] body, String useToken) throws Exception {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(client.httpBase() + "/" + path));
            if (useToken != null)
                builder.header("token", useToken);
            builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
            if (body != null)
                builder.header("Content-Type", "application/octet-stream");
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        }

        HttpResponse<byte[]> get(String path) throws Exception {
            return send("GET", path, null, token);
        }

        /** The same two calls the Figura client makes to log in. */
        void login() throws Exception {
            HttpResponse<byte[]> id = send("GET", "/auth/id?username=" + identity.name(), null, null);
            assertEquals(200, id.statusCode());
            HttpResponse<byte[]> verify = send("GET", "/auth/verify?id=" + text(id), null, null);
            assertEquals(200, verify.statusCode());
            token = text(verify);
        }

        void leave() {
            client.stop();
            server.playerLeft(identity.uuid());
            toServer.shutdownNow();
            toClient.shutdownNow();
        }
    }

    private void start(String settings) throws Exception {
        Path config = dir.resolve("figuralocal.properties");
        if (settings != null)
            Files.writeString(config, settings);
        server = new ServerSide(dir.resolve("data"), config);
    }

    @AfterEach
    void stop() {
        for (Player player : players)
            player.leave();
        if (server != null)
            server.stop();
    }

    @Test
    void uploadDownloadDelete() throws Exception {
        start(null);
        Player alice = new Player("Alice", 0);
        alice.login();

        assertEquals(200, alice.get("").statusCode());
        assertTrue(text(alice.get("limits")).contains("\"maxAvatarSize\":" + 5120 * 1024));
        assertTrue(text(alice.get("version")).contains("\"release\""));
        assertEquals(200, alice.get("motd").statusCode());

        String own = alice.identity.uuid().toString();
        assertTrue(text(alice.get(own)).contains("\"equipped\":[]"));

        byte[] avatar = randomBytes(3 * 1024 * 1024);
        assertEquals(200, alice.send("PUT", "avatar", avatar, alice.token).statusCode());
        assertEquals(200, alice.send("POST", "equip", "[]".getBytes(StandardCharsets.UTF_8), alice.token).statusCode());

        String user = text(alice.get(own));
        assertTrue(user.contains("\"id\":\"avatar\"") && user.contains("\"owner\":\"" + own + "\"") && user.contains("\"hash\":\""), user);
        HttpResponse<byte[]> downloaded = alice.get(own + "/avatar");
        assertEquals(200, downloaded.statusCode());
        assertArrayEquals(avatar, downloaded.body());

        assertEquals(200, alice.send("DELETE", "avatar", null, alice.token).statusCode());
        assertEquals(404, alice.get(own + "/avatar").statusCode());
        assertEquals(404, alice.send("DELETE", "avatar", null, alice.token).statusCode());
        assertTrue(Files.exists(dir.resolve("data/avatars")));
    }

    @Test
    void anotherPlayerSeesTheAvatar() throws Exception {
        start(null);
        Player alice = new Player("Alice", 0);
        Player bob = new Player("Bob", 0);
        alice.login();
        bob.login();

        byte[] avatar = randomBytes(200_000);
        assertEquals(200, alice.send("PUT", "avatar", avatar, alice.token).statusCode());
        assertArrayEquals(avatar, bob.get(alice.identity.uuid() + "/avatar").body());
        assertEquals(404, bob.get(bob.identity.uuid() + "/avatar").statusCode());
    }

    @Test
    void rejectsWhatItShould() throws Exception {
        start("maxAvatarSizeKb=64\n");
        Player alice = new Player("Alice", 0);
        Player bob = new Player("Bob", 0);
        alice.login();
        bob.login();
        String aliceId = alice.identity.uuid().toString();

        // no token, and a token that belongs to another player
        assertEquals(401, alice.send("GET", aliceId, null, null).statusCode());
        assertEquals(401, bob.send("GET", aliceId, null, alice.token).statusCode());
        assertEquals(401, bob.send("PUT", "avatar", randomBytes(100), alice.token).statusCode());

        // wrong secret in the path
        String wrong = alice.client.httpBase().replaceAll("/[0-9a-f]+/api$", "/00000000000000000000000000000000/api");
        HttpResponse<byte[]> forbidden = alice.http.send(HttpRequest.newBuilder(URI.create(wrong + "/limits")).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(403, forbidden.statusCode());

        // too large, then the connection still works
        assertEquals(413, alice.send("PUT", "avatar", randomBytes(64 * 1024 + 1), alice.token).statusCode());
        assertEquals(200, alice.send("PUT", "avatar", randomBytes(64 * 1024), alice.token).statusCode());

        // a login id cannot be verified by someone else
        HttpResponse<byte[]> id = alice.send("GET", "/auth/id?username=Alice", null, null);
        assertEquals(404, bob.send("GET", "/auth/verify?id=" + text(id), null, null).statusCode());

        // nothing outside the avatar folder is reachable through the path
        assertEquals(404, alice.get("..%2F..%2Ffiguralocal.properties").statusCode());
        assertEquals(404, alice.get("assets/v2").statusCode());
    }

    @Test
    void slowLinkWithSmallWindowKeepsDataIntact() throws Exception {
        start("windowKb=8\n");
        Player alice = new Player("Alice", 1);
        alice.login();
        byte[] avatar = randomBytes(300_000);
        assertEquals(200, alice.send("PUT", "avatar", avatar, alice.token).statusCode());
        assertArrayEquals(avatar, alice.get(alice.identity.uuid() + "/avatar").body());
    }

    @Test
    void pingsAndReloadEventsReachSubscribers() throws Exception {
        start(null);
        Player alice = new Player("Alice", 0);
        Player bob = new Player("Bob", 0);
        alice.login();
        bob.login();

        BlockingQueue<byte[]> aliceInbox = new LinkedBlockingQueue<>();
        BlockingQueue<byte[]> bobInbox = new LinkedBlockingQueue<>();
        WebSocket aliceWs = connect(alice, aliceInbox);
        WebSocket bobWs = connect(bob, bobInbox);
        try {
            // both are told they are authenticated
            assertArrayEquals(new byte[]{0}, aliceInbox.poll(5, TimeUnit.SECONDS));
            assertArrayEquals(new byte[]{0}, bobInbox.poll(5, TimeUnit.SECONDS));

            // Alice and Bob both follow Bob, like Figura does for every online player including yourself
            aliceWs.sendBinary(subscribe(bob.identity.uuid()));
            bobWs.sendBinary(subscribe(bob.identity.uuid()));
            Thread.sleep(200);

            byte[] payload = {9, 8, 7};
            bobWs.sendBinary(ping(42, false, payload));
            byte[] got = aliceInbox.poll(5, TimeUnit.SECONDS);
            assertNotNull(got, "Alice did not get Bob's ping");
            ByteBuffer buf = ByteBuffer.wrap(got);
            assertEquals(1, buf.get());
            assertEquals(bob.identity.uuid(), new UUID(buf.getLong(), buf.getLong()));
            assertEquals(42, buf.getInt());
            assertEquals(0, buf.get());
            byte[] rest = new byte[buf.remaining()];
            buf.get(rest);
            assertArrayEquals(payload, rest);
            assertNull(bobInbox.poll(300, TimeUnit.MILLISECONDS), "an unsynced ping must not return to its sender");

            bobWs.sendBinary(ping(43, true, payload));
            assertNotNull(aliceInbox.poll(5, TimeUnit.SECONDS));
            assertNotNull(bobInbox.poll(5, TimeUnit.SECONDS), "a synced ping returns to its sender");

            // an oversized ping gets a notice and is not passed on
            bobWs.sendBinary(ping(44, false, new byte[2000]));
            assertArrayEquals(new byte[]{5, 0}, bobInbox.poll(5, TimeUnit.SECONDS));
            assertNull(aliceInbox.poll(300, TimeUnit.MILLISECONDS));

            // equipping a new avatar tells followers to reload it
            assertEquals(200, bob.send("PUT", "avatar", randomBytes(5000), bob.token).statusCode());
            assertEquals(200, bob.send("POST", "equip", "[]".getBytes(StandardCharsets.UTF_8), bob.token).statusCode());
            byte[] event = aliceInbox.poll(5, TimeUnit.SECONDS);
            assertNotNull(event, "Alice did not get the reload event");
            ByteBuffer ev = ByteBuffer.wrap(event);
            assertEquals(2, ev.get());
            assertEquals(bob.identity.uuid(), new UUID(ev.getLong(), ev.getLong()));
        } finally {
            aliceWs.disconnect();
            bobWs.disconnect();
        }
    }

    @Test
    void websocketWithWrongTokenIsClosed() throws Exception {
        start(null);
        Player alice = new Player("Alice", 0);
        alice.login();
        BlockingQueue<byte[]> inbox = new LinkedBlockingQueue<>();
        String realToken = alice.token;
        alice.token = "not-a-token";
        WebSocket ws = connect(alice, inbox);
        try {
            assertNull(inbox.poll(500, TimeUnit.MILLISECONDS));
            for (int i = 0; i < 100 && ws.isOpen(); i++)
                Thread.sleep(20);
            assertFalse(ws.isOpen(), "connection should be closed after a bad token");
        } finally {
            alice.token = realToken;
            ws.disconnect();
        }
    }

    @Test
    void leavingEndsTheSession() throws Exception {
        start(null);
        Player alice = new Player("Alice", 0);
        alice.login();
        String token = alice.token;
        UUID id = alice.identity.uuid();
        alice.leave();
        players.remove(alice);
        assertNull(alice.client.httpBase());

        // The same player joins again: the old token no longer works.
        Player again = new Player("Alice", 0);
        again.login();
        assertEquals(401, again.send("GET", id.toString(), null, token).statusCode());
    }

    @Test
    void disabledServerSendsNoHello() throws Exception {
        start("enabled=false\n");
        Identity identity = new Identity(UUID.randomUUID(), "Alice");
        List<byte[]> sent = new ArrayList<>();
        server.playerJoined(identity, sent::add);
        assertTrue(sent.isEmpty());
    }

    // -- helpers --

    /** Connects the way Figura does: nv-websocket-client, permessage-deflate offered, token as first message. */
    private static WebSocket connect(Player player, BlockingQueue<byte[]> inbox) throws Exception {
        WebSocket ws = new WebSocketFactory().createSocket(player.client.wsBase());
        ws.addExtension(WebSocketExtension.PERMESSAGE_DEFLATE);
        ws.addListener(new WebSocketAdapter() {
            @Override
            public void onBinaryMessage(WebSocket websocket, byte[] binary) {
                inbox.add(binary);
            }
        });
        ws.connect();
        byte[] token = player.token.getBytes(StandardCharsets.UTF_8);
        ws.sendBinary(ByteBuffer.allocate(1 + token.length).put((byte) 0).put(token).array());
        return ws;
    }

    private static byte[] subscribe(UUID target) {
        return ByteBuffer.allocate(17).put((byte) 2).putLong(target.getMostSignificantBits()).putLong(target.getLeastSignificantBits()).array();
    }

    private static byte[] ping(int id, boolean sync, byte[] data) {
        return ByteBuffer.allocate(6 + data.length).put((byte) 1).putInt(id).put((byte) (sync ? 1 : 0)).put(data).array();
    }

    private static byte[] randomBytes(int size) {
        byte[] data = new byte[size];
        new Random(size).nextBytes(data);
        return data;
    }

    private static String text(HttpResponse<byte[]> response) {
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    private static void sleep(long millis) {
        if (millis <= 0)
            return;
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
