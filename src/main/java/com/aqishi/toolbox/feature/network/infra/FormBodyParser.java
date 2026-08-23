package com.aqishi.toolbox.feature.network.infra;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Parses URL-encoded query strings and request bodies without losing repeats. */
final class FormBodyParser {
    Map<String, List<String>> parse(String encoded) {
        Map<String, List<String>> values =
                new LinkedHashMap<String, List<String>>();
        if (encoded == null || encoded.length() == 0) {
            return values;
        }

        String[] pairs = encoded.split("&", -1);
        for (String pair : pairs) {
            int separator = pair.indexOf('=');
            String key = separator < 0 ? pair : pair.substring(0, separator);
            String value = separator < 0 ? "" : pair.substring(separator + 1);
            add(values, decode(key), decode(value));
        }
        return values;
    }

    private static void add(Map<String, List<String>> values, String key,
                            String value) {
        List<String> repeated = values.get(key);
        if (repeated == null) {
            repeated = new ArrayList<String>();
            values.put(key, repeated);
        }
        repeated.add(value);
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (IllegalArgumentException e) {
            return value;
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 is required by the Java runtime", e);
        }
    }
}
