package com.aqishi.toolbox.feature.network.application;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * One row of the MQTT message log. Immutable, so it can be built on a Paho thread
 * and rendered later on the EDT.
 *
 * @param direction who produced the row
 * @param time      formatted wall-clock time of the event
 * @param topic     topic, or "-" for system rows
 * @param qos       QoS level
 * @param retain    retained flag
 * @param payload   payload text, capped at {@link #MAX_PAYLOAD_CHARS}
 */
public record MqttLogEntry(Direction direction, String time, String topic, int qos, boolean retain,
                           String payload) {

    /** Longest payload kept per row; larger payloads are cut so 5000 rows stay small. */
    public static final int MAX_PAYLOAD_CHARS = 64 * 1024;

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    public enum Direction { RECEIVED, SENT, SYSTEM }

    public static MqttLogEntry of(Direction direction, String topic, int qos, boolean retain, String payload) {
        return new MqttLogEntry(direction, LocalTime.now().format(TIME), topic, qos, retain, cap(payload));
    }

    public static MqttLogEntry system(String topic, String text) {
        return of(Direction.SYSTEM, topic == null ? "-" : topic, 0, false, text);
    }

    static String cap(String payload) {
        if (payload == null) {
            return "";
        }
        if (payload.length() <= MAX_PAYLOAD_CHARS) {
            return payload;
        }
        int cut = MAX_PAYLOAD_CHARS;
        if (Character.isHighSurrogate(payload.charAt(cut - 1))) {
            cut--;
        }
        return payload.substring(0, cut) + " ... [+" + (payload.length() - cut) + " chars]";
    }
}
