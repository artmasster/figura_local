package io.github.artmasster.figuralocal.core.backend;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Keeps one avatar file per player in a folder, named {@code <uuid>.moon}. */
public final class AvatarStore {

    private final Path dir;
    private final Map<UUID, String> hashes = new ConcurrentHashMap<>();

    public AvatarStore(Path dir) throws IOException {
        this.dir = dir;
        Files.createDirectories(dir);
    }

    private Path file(UUID owner) {
        // The name comes from a parsed UUID, so it cannot point outside the folder.
        return dir.resolve(owner + ".moon");
    }

    public void put(UUID owner, byte[] data) throws IOException {
        Path tmp = Files.createTempFile(dir, owner.toString(), ".tmp");
        try {
            Files.write(tmp, data);
            Files.move(tmp, file(owner), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
        hashes.put(owner, sha256(data));
    }

    /** Returns the avatar, or null when the player has none. */
    public byte[] get(UUID owner) throws IOException {
        try {
            return Files.readAllBytes(file(owner));
        } catch (NoSuchFileException e) {
            return null;
        }
    }

    public boolean delete(UUID owner) throws IOException {
        hashes.remove(owner);
        return Files.deleteIfExists(file(owner));
    }

    /** Returns the SHA-256 of the avatar, or null when the player has none. */
    public String hash(UUID owner) throws IOException {
        String known = hashes.get(owner);
        if (known != null)
            return known;
        byte[] data = get(owner);
        if (data == null)
            return null;
        String hash = sha256(data);
        hashes.put(owner, hash);
        return hash;
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
