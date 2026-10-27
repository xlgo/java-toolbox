package com.aqishi.toolbox.infra.network.scan;

import com.aqishi.toolbox.feature.network.domain.ScanProfile;
import com.aqishi.toolbox.infra.config.JsonPreferencesStore;
import com.fasterxml.jackson.core.type.TypeReference;

import java.util.*;
import java.util.prefs.Preferences;

/** Only port groups and performance parameters are persisted. Targets remain session-only. */
public final class PortScanPreferences {
    private final Preferences preferences;
    private final JsonPreferencesStore<String> groups;
    private final JsonPreferencesStore<String> textStore;
    private final LinkedHashMap<String, String> largeText;
    private final JsonPreferencesStore<ScanProfile> profiles;

    public PortScanPreferences(Preferences preferences) {
        this.preferences = preferences;
        groups =
                new JsonPreferencesStore<>(
                        preferences,
                        "portscan.groups",
                        new TypeReference<LinkedHashMap<String, String>>() {});
        textStore =
                new JsonPreferencesStore<>(
                        preferences,
                        "portscan.largeText",
                        new TypeReference<LinkedHashMap<String, String>>() {});
        largeText = textStore.load();
        profiles =
                new JsonPreferencesStore<>(
                        preferences,
                        "portscan.profiles",
                        new TypeReference<LinkedHashMap<String, ScanProfile>>() {});
    }

    public LinkedHashMap<String, String> groups() {
        return groups.load();
    }

    public void saveGroups(Map<String, String> values) {
        groups.save(values);
    }

    public LinkedHashMap<String, ScanProfile> profiles() {
        return profiles.load();
    }

    public void saveProfiles(Map<String, ScanProfile> values) {
        profiles.save(values);
    }

    public int get(String name, int fallback, int min, int max) {
        return Math.max(min, Math.min(max, preferences.getInt("portscan." + name, fallback)));
    }

    public void set(String name, int value) {
        preferences.putInt("portscan." + name, value);
    }

    public String get(String name, String fallback) {
        return largeText.getOrDefault(name, preferences.get("portscan." + name, fallback));
    }

    public void set(String name, String value) {
        if (value.length() > Preferences.MAX_VALUE_LENGTH) {
            LinkedHashMap<String, String> replacement = new LinkedHashMap<>(largeText);
            replacement.put(name, value);
            textStore.save(replacement);
            largeText.clear();
            largeText.putAll(replacement);
            preferences.remove("portscan." + name);
        } else {
            preferences.put("portscan." + name, value);
            if (largeText.containsKey(name)) {
                LinkedHashMap<String, String> replacement = new LinkedHashMap<>(largeText);
                replacement.remove(name);
                textStore.save(replacement);
                largeText.clear();
                largeText.putAll(replacement);
            }
        }
    }
}
