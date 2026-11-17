package io.github.artmasster.figuralocal.core.backend;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Settings of the avatar backend, read from {@code config/figuralocal.properties}. */
public final class BackendConfig {

    /** Turns the whole mod off on this server without removing the jar. */
    public boolean enabled = true;
    /** Largest avatar a player may upload, in kibibytes. Figura's own cloud allows 100. */
    public int maxAvatarSizeKb = 5120;
    /** Largest ping payload in bytes. */
    public int pingSize = 1024;
    /** Pings a player may send per second. */
    public int pingRate = 32;
    /** Unacknowledged kibibytes allowed per transfer. Lower values favour game packets on slow links. */
    public int windowKb = 128;
    /** Message shown in Figura's backend status. Empty for none. */
    public String motd = "";

    public int maxAvatarBytes() {
        return (int) Math.min(Integer.MAX_VALUE, maxAvatarSizeKb * 1024L);
    }

    public int windowBytes() {
        return Math.max(8, windowKb) * 1024;
    }

    private static final String TEMPLATE = """
            # FiguraLocal settings. Restart the server after changing them.

            # Set to false to switch FiguraLocal off without removing the jar.
            enabled=%s

            # Largest avatar a player may upload, in KiB. Figura's own cloud allows 100.
            maxAvatarSizeKb=%d

            # Largest ping payload in bytes, and pings allowed per player per second.
            pingSize=%d
            pingRate=%d

            # Avatar data travels over the game connection. This is how many KiB may be
            # in flight per transfer; lower it if players on slow links lag during uploads.
            windowKb=%d

            # Message shown in Figura's backend status screen. Leave empty for none.
            motd=%s
            """;

    /** Reads the file, creating it with defaults when it does not exist. Bad values fall back to defaults. */
    public static BackendConfig load(Path file) throws IOException {
        BackendConfig config = new BackendConfig();
        if (Files.exists(file)) {
            Properties props = new Properties();
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                props.load(reader);
            }
            config.enabled = !"false".equalsIgnoreCase(props.getProperty("enabled", "true").trim());
            config.maxAvatarSizeKb = number(props, "maxAvatarSizeKb", config.maxAvatarSizeKb, 1, 1024 * 1024);
            config.pingSize = number(props, "pingSize", config.pingSize, 16, 65536);
            config.pingRate = number(props, "pingRate", config.pingRate, 1, 1000);
            config.windowKb = number(props, "windowKb", config.windowKb, 8, 4096);
            config.motd = props.getProperty("motd", "").trim();
        } else {
            if (file.getParent() != null)
                Files.createDirectories(file.getParent());
            Files.writeString(file, TEMPLATE.formatted(config.enabled, config.maxAvatarSizeKb, config.pingSize,
                    config.pingRate, config.windowKb, config.motd), StandardCharsets.UTF_8);
        }
        return config;
    }

    private static int number(Properties props, String key, int fallback, int min, int max) {
        try {
            int value = Integer.parseInt(props.getProperty(key, String.valueOf(fallback)).trim());
            return Math.max(min, Math.min(max, value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
