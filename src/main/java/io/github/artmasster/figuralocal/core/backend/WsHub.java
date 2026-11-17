package io.github.artmasster.figuralocal.core.backend;

import io.github.artmasster.figuralocal.core.http.WsConnection;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * The WebSocket half of the backend: players subscribe to each other, pings from one player are
 * passed to everyone subscribed to them, and an event tells subscribers to reload an avatar.
 * Message layouts follow the Figura client.
 */
final class WsHub {

    // client to server
    private static final byte C2S_TOKEN = 0, C2S_PING = 1, C2S_SUB = 2, C2S_UNSUB = 3;
    // server to client
    private static final byte S2C_AUTH = 0, S2C_PING = 1, S2C_EVENT = 2, S2C_NOTICE = 5;
    private static final byte NOTICE_PING_SIZE = 0, NOTICE_PING_RATE = 1;

    private static final int MAX_SUBSCRIPTIONS = 2048;
    private static final int QUEUE_SIZE = 512;
    private static final byte[] STOP = new byte[0];
    private static final AtomicInteger THREADS = new AtomicInteger();

    private final BackendConfig config;
    private final Function<String, Identity> tokens;
    private final Map<UUID, Set<Client>> subscribers = new ConcurrentHashMap<>();
    private final ExecutorService writers = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "FiguraLocal-ws-" + THREADS.incrementAndGet());
        t.setDaemon(true);
        return t;
    });

    WsHub(BackendConfig config, Function<String, Identity> tokens) {
        this.config = config;
        this.tokens = tokens;
    }

    WsConnection.Listener newClient(Identity identity) {
        return new Client(identity);
    }

    /** Tells everyone subscribed to this player that the avatar changed. */
    void avatarChanged(UUID owner) {
        byte[] message = ByteBuffer.allocate(17).put(S2C_EVENT)
                .putLong(owner.getMostSignificantBits()).putLong(owner.getLeastSignificantBits()).array();
        Set<Client> targets = subscribers.get(owner);
        if (targets != null) {
            for (Client target : targets)
                target.send(message);
        }
    }

    void close() {
        writers.shutdownNow();
    }

    private final class Client implements WsConnection.Listener {
        private final Identity identity;
        private final Set<UUID> subscriptions = ConcurrentHashMap.newKeySet();
        // Each player has an own queue and writer, so one slow player cannot hold up the others.
        private final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>(QUEUE_SIZE);
        private volatile WsConnection connection;
        private volatile boolean authed;
        private long rateWindowStart;
        private int pingsInWindow;

        Client(Identity identity) {
            this.identity = identity;
        }

        void send(byte[] message) {
            // A full queue means the player cannot keep up; pings are dropped rather than buffered.
            queue.offer(message);
        }

        @Override
        public void onOpen(WsConnection connection) {
            this.connection = connection;
            writers.execute(this::writeLoop);
        }

        private void writeLoop() {
            try {
                while (true) {
                    byte[] message = queue.take();
                    if (message == STOP)
                        return;
                    connection.sendBinary(message);
                }
            } catch (IOException | InterruptedException ignored) {
                // Connection gone or server stopping.
            }
        }

        @Override
        public void onBinary(WsConnection connection, byte[] message) {
            if (message.length == 0)
                return;
            ByteBuffer buf = ByteBuffer.wrap(message);
            byte type = buf.get();

            if (type == C2S_TOKEN) {
                String token = new String(message, 1, message.length - 1, StandardCharsets.UTF_8);
                Identity owner = tokens.apply(token);
                if (owner != null && owner.uuid().equals(identity.uuid())) {
                    authed = true;
                    send(new byte[]{S2C_AUTH});
                } else {
                    connection.close(3000, "Unauthorized");
                }
                return;
            }
            if (!authed)
                return;

            switch (type) {
                case C2S_PING -> ping(buf);
                case C2S_SUB -> {
                    if (buf.remaining() >= 16 && subscriptions.size() < MAX_SUBSCRIPTIONS) {
                        UUID target = new UUID(buf.getLong(), buf.getLong());
                        subscriptions.add(target);
                        subscribers.compute(target, (k, set) -> {
                            if (set == null)
                                set = ConcurrentHashMap.newKeySet();
                            set.add(this);
                            return set;
                        });
                    }
                }
                case C2S_UNSUB -> {
                    if (buf.remaining() >= 16)
                        unsubscribe(new UUID(buf.getLong(), buf.getLong()));
                }
                default -> {
                }
            }
        }

        private void ping(ByteBuffer buf) {
            if (buf.remaining() < 5)
                return;
            int id = buf.getInt();
            boolean sync = buf.get() != 0;
            byte[] data = new byte[buf.remaining()];
            buf.get(data);

            if (data.length > config.pingSize) {
                send(new byte[]{S2C_NOTICE, NOTICE_PING_SIZE});
                return;
            }
            long now = System.currentTimeMillis();
            if (now - rateWindowStart >= 1000) {
                rateWindowStart = now;
                pingsInWindow = 0;
            }
            if (++pingsInWindow > config.pingRate) {
                if (pingsInWindow == config.pingRate + 1)
                    send(new byte[]{S2C_NOTICE, NOTICE_PING_RATE});
                return;
            }

            UUID sender = identity.uuid();
            byte[] out = ByteBuffer.allocate(22 + data.length).put(S2C_PING)
                    .putLong(sender.getMostSignificantBits()).putLong(sender.getLeastSignificantBits())
                    .putInt(id).put((byte) (sync ? 1 : 0)).put(data).array();
            Set<Client> targets = subscribers.get(sender);
            if (targets == null)
                return;
            for (Client target : targets) {
                // A synced ping also goes back to the sender.
                if (sync || target != this)
                    target.send(out);
            }
        }

        private void unsubscribe(UUID target) {
            subscriptions.remove(target);
            subscribers.computeIfPresent(target, (k, set) -> {
                set.remove(this);
                return set.isEmpty() ? null : set;
            });
        }

        @Override
        public void onClose(WsConnection connection) {
            for (UUID target : subscriptions.toArray(new UUID[0]))
                unsubscribe(target);
            queue.clear();
            queue.offer(STOP);
        }
    }
}
