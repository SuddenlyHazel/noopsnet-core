// SPDX-License-Identifier: MPL-2.0
package net.noopsnet.core;

import java.util.Set;

/** Defaults for a newly installed emote wheel; explicit player choices always win. */
public final class EmoteKeyPolicy {
    public static final String WHEEL = "key.emotecraft.fastchoose";
    public static final String PREFERRED = "key.keyboard.i";

    private EmoteKeyPolicy() {}

    public static boolean alreadyConfigured(String options) {
        return options.lines().anyMatch(line -> line.startsWith("key_" + WHEEL + ":"));
    }

    public static boolean shouldAssign(boolean configured, String activeKey, Set<String> occupied) {
        return !configured && activeKey.equals("key.keyboard.b") && !occupied.contains(PREFERRED);
    }
}
