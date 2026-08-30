package com.aqishi.toolbox.feature.monitor.domain;

import java.security.SecureRandom;

/**
 * Generates the session identifiers used to pair a controller with a host.
 *
 * <p>These identifiers are the only thing separating a screen-sharing session
 * from an attacker joining it: anyone who can guess a live ID can ask to
 * control that machine. {@code Math.random()} is seeded predictably and is not
 * designed to resist guessing, so identifiers are drawn from
 * {@link SecureRandom} instead.</p>
 *
 * <p>The alphabet excludes look-alike characters (0/O, 1/I/L) because the ID is
 * read aloud or retyped by a human on the other end.</p>
 */
public final class RemoteSessionIds {

    private static final char[] ALPHABET =
            "23456789ABCDEFGHJKMNPQRSTUVWXYZ".toCharArray();
    private static final int LENGTH = 12;
    private static final SecureRandom RANDOM = new SecureRandom();

    private RemoteSessionIds() {
    }

    /**
     * Returns a new identifier such as {@code RD-7KQ2M4XB9TPA}.
     *
     * <p>12 characters from a 31-symbol alphabet give about 60 bits of entropy,
     * which puts brute-force enumeration out of reach while staying short
     * enough to read over the phone.</p>
     */
    public static String generate() {
        StringBuilder id = new StringBuilder(LENGTH + 3).append("RD-");
        for (int i = 0; i < LENGTH; i++) {
            id.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return id.toString();
    }
}
