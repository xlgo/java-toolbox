package com.aqishi.toolbox.feature.network.domain;

import java.util.*;

/** Attachment paths, not file contents, are stored with encrypted request templates. */
public record HttpBody(Mode mode, List<Part> parts) {
    public enum Mode {
        RAW,
        FORM,
        MULTIPART
    }

    public record Part(String name, String value, boolean file) {
        public Part {
            Objects.requireNonNull(name);
            Objects.requireNonNull(value);
        }
    }

    public HttpBody {
        mode = mode == null ? Mode.RAW : mode;
        parts = parts == null ? List.of() : List.copyOf(parts);
    }

    public static HttpBody raw() {
        return new HttpBody(Mode.RAW, List.of());
    }
}
