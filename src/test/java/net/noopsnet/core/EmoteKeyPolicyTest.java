// SPDX-License-Identifier: MPL-2.0
package net.noopsnet.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.Set;

final class EmoteKeyPolicyTest {
    @Test
    void newlyAddedWheelGetsFreeDefault() {
        assertFalse(EmoteKeyPolicy.alreadyConfigured("key_key.inventory:key.keyboard.e\n"));
        assertTrue(EmoteKeyPolicy.shouldAssign(false, "key.keyboard.b", Set.of("key.keyboard.h")));
    }

    @Test
    void playerChoicesUnboundChoicesAndOccupiedKeysArePreserved() {
        assertTrue(
                EmoteKeyPolicy.alreadyConfigured(
                        "key_key.emotecraft.fastchoose:key.keyboard.unknown\n"));
        assertFalse(EmoteKeyPolicy.shouldAssign(true, "key.keyboard.b", Set.of()));
        assertFalse(EmoteKeyPolicy.shouldAssign(false, "key.keyboard.unknown", Set.of()));
        assertFalse(EmoteKeyPolicy.shouldAssign(false, "key.keyboard.b", Set.of("key.keyboard.i")));
    }
}
