// SPDX-License-Identifier: MPL-2.0
package net.noopsnet.core;

import com.google.gson.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** World-scoped storage. Writes complete before a command reports success. */
public final class HomeStore {
    public record Home(
            String name, String dimension, double x, double y, double z, float yaw, float pitch) {
        public Home {
            if (name == null
                    || !name.matches("[a-zA-Z0-9_-]{1,32}")
                    || dimension == null
                    || !dimension.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")
                    || !Double.isFinite(x)
                    || !Double.isFinite(y)
                    || !Double.isFinite(z)
                    || !Float.isFinite(yaw)
                    || !Float.isFinite(pitch)
                    || Math.abs(x) > 30_000_000
                    || Math.abs(z) > 30_000_000)
                throw new IllegalArgumentException("Invalid home data");
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path path;
    private Map<UUID, Home> homes = new HashMap<>();

    public HomeStore(Path path) throws IOException {
        this.path = path;
        if (!Files.exists(path)) return;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            if (root.get("schema").getAsInt() != 1)
                throw new IllegalArgumentException("Unknown schema");
            for (var entry : root.getAsJsonObject("homes").entrySet()) {
                Home home = GSON.fromJson(entry.getValue(), Home.class);
                if (home == null) throw new IllegalArgumentException("Null home");
                homes.put(UUID.fromString(entry.getKey()), home);
            }
        } catch (RuntimeException ex) {
            throw new IOException("Invalid home store; refusing to overwrite " + path, ex);
        }
    }

    public Home get(UUID player) {
        return homes.get(player);
    }

    public void set(UUID player, Home home) throws IOException {
        Map<UUID, Home> next = new HashMap<>(homes);
        if (home == null) next.remove(player);
        else next.put(player, home);
        JsonObject root = new JsonObject();
        root.addProperty("schema", 1);
        JsonObject data = new JsonObject();
        next.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> data.add(e.getKey().toString(), GSON.toJsonTree(e.getValue())));
        root.add("homes", data);
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), "homes-", ".tmp");
        try {
            Files.writeString(temporary, GSON.toJson(root) + "\n");
            // No non-atomic fallback: keep the old file intact if the filesystem cannot replace
            // atomically.
            Files.move(
                    temporary,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            homes = next;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
