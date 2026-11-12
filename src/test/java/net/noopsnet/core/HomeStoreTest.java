// SPDX-License-Identifier: MPL-2.0
package net.noopsnet.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.UUID;

class HomeStoreTest {
    @TempDir Path dir;

    HomeStore.Home home(String name) {
        return new HomeStore.Home(name, "minecraft:the_nether", 12.5, 70, -21.5, 90, 15);
    }

    @Test
    void replacementDeletionAndPlayerIsolationSurviveRestart() throws Exception {
        Path file = dir.resolve("world/homes.json");
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        HomeStore store = new HomeStore(file);
        store.set(a, home("old"));
        store.set(b, home("neighbor"));
        store.set(a, home("new"));
        store = new HomeStore(file);
        assertEquals(home("new"), store.get(a));
        assertEquals(home("neighbor"), store.get(b));
        store.set(a, null);
        store = new HomeStore(file);
        assertNull(store.get(a));
        assertEquals(home("neighbor"), store.get(b));
    }

    @Test
    void corruptOrFutureDataIsNeverOverwritten() throws Exception {
        Path file = dir.resolve("homes.json");
        for (String data : new String[] {"broken", "{\"schema\":2,\"homes\":{}}"}) {
            Files.writeString(file, data);
            assertThrows(java.io.IOException.class, () -> new HomeStore(file));
            assertEquals(data, Files.readString(file));
        }
    }

    @Test
    void failedWriteDoesNotChangeInMemoryHome() throws Exception {
        Path parent = dir.resolve("blocked");
        HomeStore store = new HomeStore(parent.resolve("homes.json"));
        Files.writeString(parent, "not a directory");
        UUID id = UUID.randomUUID();
        assertThrows(java.io.IOException.class, () -> store.set(id, home("test")));
        assertNull(store.get(id));
    }

    @Test
    void invalidCoordinatesAndNamesRejected() {
        assertThrows(IllegalArgumentException.class, () -> home("../bad"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new HomeStore.Home("home", "minecraft:overworld", Double.NaN, 70, 0, 0, 0));
    }
}
