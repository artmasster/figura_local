package io.github.artmasster.figuralocal.forge;

import io.github.artmasster.figuralocal.client.ClientHooks;
import net.minecraft.network.Connection;

/**
 * The only way common code reaches client code. It is called through DistExecutor, so a
 * dedicated server never loads the client classes behind it.
 */
final class ClientBridge {

    private ClientBridge() {
    }

    static void frameFromServer(byte[] frame, Connection connection) {
        ClientHooks.frameFromServer(frame, connection);
    }

    static void register() {
        ClientHooks.register();
    }
}
