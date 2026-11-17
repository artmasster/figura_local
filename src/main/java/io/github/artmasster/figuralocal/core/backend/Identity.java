package io.github.artmasster.figuralocal.core.backend;

import java.util.UUID;

/** The player a tunnel belongs to, as the Minecraft server knows them. */
public record Identity(UUID uuid, String name) {
}
