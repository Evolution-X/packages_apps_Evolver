/*
 * Copyright (C) 2026 AxionOS
 * Copyright (C) 2026 VoltageOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.evolution.settings.fragments.miscellaneous;

import android.app.ActivityManager;
import android.content.Context;
import android.os.SystemProperties;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class PifManager {

    private static final String TAG = "PifManager";
    private static final String PIF_DIR = "/data/adb/playintegrityfix";
    private static final String VENDING_PKG = "com.android.vending";

    private static final List<String> CONFIG_FILES = Arrays.asList(
            "custom.pif.prop",
            "custom.pif.json",
            "pif.prop",
            "pif.json");

    private final Context mContext;

    public PifManager(Context context) {
        mContext = context.getApplicationContext();
        ensureDir();
    }

    public String getActiveConfigName() {
        File active = findActiveFile();
        return active != null ? active.getName() : "";
    }

    public String getCurrentModel() {
        return getCurrentProperties().getOrDefault("MODEL", "");
    }

    public List<ConfigState> getConfigStates() {
        List<ConfigState> states = new ArrayList<>();
        File dir = new File(PIF_DIR);
        boolean foundActive = false;
        for (String name : CONFIG_FILES) {
            File file = new File(dir, name);
            boolean exists = file.exists() && file.canRead();
            boolean active = exists && !foundActive;
            if (active) {
                foundActive = true;
            }
            states.add(new ConfigState(name, exists, active,
                    exists ? readConfig(file) : Collections.emptyMap()));
        }
        return states;
    }

    public Map<String, String> getCurrentProperties() {
        File active = findActiveFile();
        if (active == null) {
            return Collections.emptyMap();
        }
        return readConfig(active);
    }

    public void applyPif(JSONObject pifData) throws Exception {
        writeAutoSelectedJsonConfig(pifData);
    }

    public String importPifConfig(String sourceName, String content) throws Exception {
        return importPifConfig(null, sourceName, content);
    }

    public String importPifConfig(String targetFileName, String sourceName, String content) throws Exception {
        ensureDir();

        boolean isJson = looksLikeJson(sourceName, content);
        Map<String, String> props = parseConfigContent(sourceName, content);
        if (props.isEmpty()) {
            throw new IllegalArgumentException("Config contains no readable properties");
        }

        String destinationName = targetFileName;
        if (destinationName == null || destinationName.trim().isEmpty()) {
            destinationName = resolveAutoTargetFile(isJson);
        }
        boolean destinationIsJson = destinationName.toLowerCase().endsWith(".json");

        File target = new File(PIF_DIR, destinationName);
        try (FileWriter writer = new FileWriter(target)) {
            writer.write(destinationIsJson
                    ? serializeJsonConfig(props)
                    : serializePropConfig(props));
        }
        target.setReadable(true, false);
        killPackage(VENDING_PKG);
        Log.i(TAG, "Imported PIF config: " + target.getAbsolutePath());
        return target.getName();
    }

    public void writeJsonConfig(String fileName, JSONObject pifData) throws Exception {
        Map<String, String> props = new LinkedHashMap<>();
        Iterator<String> keys = pifData.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = pifData.opt(key);
            props.put(key, value == null ? "" : String.valueOf(value));
        }
        writeConfig(fileName, props);
    }

    public String writeAutoSelectedJsonConfig(JSONObject pifData) throws Exception {
        Map<String, String> props = new LinkedHashMap<>();
        Iterator<String> keys = pifData.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = pifData.opt(key);
            props.put(key, value == null ? "" : String.valueOf(value));
        }
        String targetFileName = resolveAutoTargetFile(true);
        writeConfig(targetFileName, props);
        return targetFileName;
    }

    private void writeConfig(String fileName, Map<String, String> props) throws Exception {
        ensureDir();
        File target = new File(PIF_DIR, fileName);
        try (FileWriter writer = new FileWriter(target)) {
            writer.write(fileName.toLowerCase().endsWith(".json")
                    ? serializeJsonConfig(props)
                    : serializePropConfig(props));
        }
        target.setReadable(true, false);
        killPackage(VENDING_PKG);
        Log.i(TAG, "Applied PIF config: " + target.getAbsolutePath());
    }

    public void deleteConfig(String fileName) {
        File target = new File(PIF_DIR, fileName);
        if (target.exists() && !target.delete()) {
            Log.w(TAG, "Failed to delete config: " + target.getAbsolutePath());
        }
        killPackage(VENDING_PKG);
    }

    public static boolean looksLikeJson(String fileName, String content) {
        if (fileName != null) {
            String lowerName = fileName.toLowerCase();
            if (lowerName.endsWith(".json")) {
                return true;
            }
            if (lowerName.endsWith(".prop")) {
                return false;
            }
        }
        String trimmed = content != null ? content.trim() : "";
        return trimmed.startsWith("{");
    }

    public static Map<String, String> parseConfigContent(String fileName, String content)
            throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        if (looksLikeJson(fileName, content)) {
            JSONObject json = new JSONObject(content);
            Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object value = json.opt(key);
                result.put(key, value == null ? "" : String.valueOf(value));
            }
            return result;
        }

        for (String line : content.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("//")) {
                continue;
            }

            int eq = trimmed.indexOf('=');
            if (eq <= 0) {
                continue;
            }

            String key = trimmed.substring(0, eq).trim();
            String value = trimmed.substring(eq + 1).trim();
            int commentIndex = value.indexOf('#');
            if (commentIndex >= 0) {
                value = value.substring(0, commentIndex).trim();
            }

            result.put(key, stripWrappingQuotes(value));
        }
        return result;
    }

    private void ensureDir() {
        File dir = new File(PIF_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    private File findActiveFile() {
        File dir = new File(PIF_DIR);
        for (String name : CONFIG_FILES) {
            File file = new File(dir, name);
            if (file.exists() && file.canRead()) {
                return file;
            }
        }
        return null;
    }

    private String resolveAutoTargetFile(boolean preferJson) {
        File active = findActiveFile();
        if (active != null) {
            return active.getName();
        }
        return preferJson ? "custom.pif.json" : "custom.pif.prop";
    }

    private Map<String, String> readConfig(File file) {
        try {
            return parseConfigContent(file.getName(), readFileToString(file));
        } catch (Exception e) {
            Log.e(TAG, "Failed to read config: " + file.getAbsolutePath(), e);
            return Collections.emptyMap();
        }
    }

    private File ensureEditableConfig() {
        ensureDir();
        File active = findActiveFile();
        if (active != null && active.getName().startsWith("custom.")) {
            return active;
        }

        try {
            Map<String, String> currentProps = active != null
                    ? readConfig(active)
                    : Collections.emptyMap();
            deleteCustomOverrides();

            File editable = new File(PIF_DIR, "custom.pif.json");
            JSONObject json = new JSONObject();
            for (Map.Entry<String, String> entry : currentProps.entrySet()) {
                json.put(entry.getKey(), entry.getValue());
            }

            try (FileWriter writer = new FileWriter(editable)) {
                writer.write(json.toString(2));
            }
            editable.setReadable(true, false);
            return editable;
        } catch (Exception e) {
            Log.e(TAG, "Failed to create editable config", e);
            return null;
        }
    }

    private void updateConfigKey(File file, String key, String value) {
        try {
            String updated;
            String content = readFileToString(file);
            if (looksLikeJson(file.getName(), content)) {
                JSONObject json = content.trim().isEmpty() ? new JSONObject() : new JSONObject(content);
                json.put(key, value);
                updated = json.toString(2);
            } else {
                StringBuilder builder = new StringBuilder();
                boolean found = false;
                for (String line : content.split("\n")) {
                    String trimmed = line.trim();
                    if (!trimmed.startsWith("#") && trimmed.startsWith(key + "=")) {
                        builder.append(key).append("=").append(value).append("\n");
                        found = true;
                    } else if (!line.isEmpty()) {
                        builder.append(line).append("\n");
                    }
                }
                if (!found) {
                    builder.append(key).append("=").append(value).append("\n");
                }
                updated = builder.toString();
            }

            try (FileWriter writer = new FileWriter(file)) {
                writer.write(updated);
            }
            file.setReadable(true, false);
        } catch (Exception e) {
            Log.e(TAG, "Failed to update config key: " + key, e);
        }
    }

    private String readFileToString(File file) throws Exception {
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append("\n");
            }
        }
        return builder.toString();
    }

    private void deleteCustomOverrides() {
        File customProp = new File(PIF_DIR, "custom.pif.prop");
        if (customProp.exists()) {
            customProp.delete();
        }

        File customJson = new File(PIF_DIR, "custom.pif.json");
        if (customJson.exists()) {
            customJson.delete();
        }
    }

    private String serializeJsonConfig(Map<String, String> props) throws Exception {
        JSONObject json = new JSONObject();
        for (Map.Entry<String, String> entry : props.entrySet()) {
            json.put(entry.getKey(), entry.getValue());
        }
        return json.toString(2);
    }

    private String serializePropConfig(Map<String, String> props) {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : props.entrySet()) {
            builder.append(entry.getKey())
                    .append("=")
                    .append(entry.getValue())
                    .append("\n");
        }
        return builder.toString();
    }

    private static String stripWrappingQuotes(String value) {
        if (value == null || value.length() < 2) {
            return value;
        }
        if ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'"))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private void killPackage(String packageName) {
        try {
            ActivityManager activityManager = (ActivityManager)
                    mContext.getSystemService(Context.ACTIVITY_SERVICE);
            if (activityManager != null) {
                activityManager.forceStopPackage(packageName);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to stop package: " + packageName, e);
        }
    }

    public static final class ConfigState {
        public final String fileName;
        public final boolean exists;
        public final boolean isActive;
        public final Map<String, String> data;

        ConfigState(String fileName, boolean exists, boolean isActive, Map<String, String> data) {
            this.fileName = fileName;
            this.exists = exists;
            this.isActive = isActive;
            this.data = data;
        }
    }
}
