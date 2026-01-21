package org.evolution.settings.utils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.ThreadLocalRandom;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SpoofingUtils {

    private static volatile String PIXEL_VERSIONS_HTML;
    private static volatile String PIXEL_LATEST_HTML;
    private static volatile String PIXEL_FLASH_HTML;
    private static volatile String PIXEL_FACTORY_IMAGE_HTML;
    private static volatile String FLASH_KEY;

    private static volatile String LATEST_BUILD_URL;
    
    private static final String ANDROID_FLASH_URL = "https://flash.android.com/";
    private static final String ANDROID_DEV_URL = "https://developer.android.com/";
    private static final String ANDROID_DEV_VERSIONS_URL = ANDROID_DEV_URL + "about/versions";
    private static final String PIXEL_SEC_BULLETIN_URL = "https://source.android.com/docs/security/bulletin/pixel";
    
    private static final String PIXEL_STATION_URL = "https://content-flashstation-pa.googleapis.com/v1/builds";

    private static Map<String, String> betaDevices;
    private static String canaryId;
    private static Map<String, String> buildMeta = new LinkedHashMap<>();


    private static class Regex {

        private static final Pattern DEVICE_ROW = Pattern.compile(
                "<tr\\s+id=\"([^\"]+)\"[^>]*>\\s*<td>([^<]+)</td>",
                Pattern.CASE_INSENSITIVE
        );
        private static final Pattern DATA_CLIENT_CONFIG_PATTERN = 
                Pattern.compile(
                    "data-client-config\\s*=\\s*\"(?:[^,]*,){2}\\s*&quot;([^&]+)&quot;",
                    Pattern.CASE_INSENSITIVE
        );
        private static final Pattern RC_NAME =
                Pattern.compile("\"releaseCandidateName\"\\s*:\\s*\"([^\"]+)\"");
        private static final Pattern BUILD_ID =
                Pattern.compile("\"buildId\"\\s*:\\s*\"([^\"]+)\"");

        private static final Pattern SECURITY_PATCH = Pattern.compile(
                "<td>%s</td>\\s*<td>([^<]+)</td>"
        );
        private static final Pattern CANARY_ID = Pattern.compile(
                "\"id\"\\s*:\\s*\"canary-([^\"]+)\""
        );

        private static final Pattern IS_CANARY_PATTERN = Pattern.compile(
        "\\{[^}]*\"canary\"\\s*:\\s*true[^}]*\\}", Pattern.CASE_INSENSITIVE | Pattern.DOTALL
        );

    }


    private static String getHTML(String url, Map<String, String> headers)
            throws IOException {

        HttpURLConnection conn =
                (HttpURLConnection) new URL(url).openConnection();

        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(10_000);

        if (headers != null) {
            for (var e : headers.entrySet()) {
                conn.setRequestProperty(e.getKey(), e.getValue());
            }
        }

        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(conn.getInputStream(),
                        StandardCharsets.UTF_8))) {

            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        }
    }

    private static String unescapeHtml(String s) {
    if (s == null) return null;
    return s.replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">");
}

    private static List<String> extractLinks(String html, Pattern filter) {
        List<String> links = new ArrayList<>();
        Matcher m = filter.matcher(html);

        while (m.find()) {
            links.add(m.group());
        }
        return links;
    }

    private static String pickLastSorted(List<String> links) {
        if (links.isEmpty()) return null;
        Collections.sort(links);
        return links.getLast();
    }

    private static void getLatestCanary() throws IOException {
        PIXEL_VERSIONS_HTML = getHTML(ANDROID_DEV_VERSIONS_URL, null);

        Pattern p = Pattern.compile(ANDROID_DEV_VERSIONS_URL + "/.*[0-9]");
        List<String> links;
        links = extractLinks(PIXEL_VERSIONS_HTML, p);

        LATEST_BUILD_URL = pickLastSorted(links);
    }

    private static void getFactoryImageInformation() throws IOException {
        PIXEL_LATEST_HTML = getHTML(LATEST_BUILD_URL, null);

        Matcher m = Pattern.compile("href=\"(.*download.*)\"").matcher(PIXEL_LATEST_HTML);
        String FACTORY_IMAGE_URL = m.find() ? m.group(1) : null;

        assert FACTORY_IMAGE_URL != null;
        if (!FACTORY_IMAGE_URL.isEmpty()) {
            PIXEL_FACTORY_IMAGE_HTML = getHTML(ANDROID_DEV_URL + FACTORY_IMAGE_URL, null);
        }
    }

    private static void parsePixelFiDevices() {
        Map<String, String> devices = new LinkedHashMap<>();

        Matcher m = Regex.DEVICE_ROW.matcher(PIXEL_FACTORY_IMAGE_HTML);
        while (m.find()) {
            devices.put(
                    Objects.requireNonNull(m.group(2)).trim(),
                    Objects.requireNonNull(m.group(1)).trim() + "_beta"
            );
        }

        betaDevices = devices;
    }

    public static void getBuildInfo(String deviceName) throws Exception {
        PIXEL_FLASH_HTML = getHTML(ANDROID_FLASH_URL, null);

        String product = betaDevices.get(deviceName);
        String device = product.replace("_beta","");

        Matcher m = Regex.DATA_CLIENT_CONFIG_PATTERN.matcher(PIXEL_FLASH_HTML);
        if (!m.find()) {
            throw new IllegalStateException("Failed to extract flash key");
        }
    
        FLASH_KEY = unescapeHtml(m.group(1));

        Map<String, String> headers = Map.of(
                "Referer", ANDROID_FLASH_URL
        );

        String stationJson = getHTML(
                PIXEL_STATION_URL
                        + "?product=" + product
                        + "&key=" + FLASH_KEY,
                headers
        );

        String buildId = null;
        String buildIncremental = null;

        Matcher rc = Regex.RC_NAME.matcher(stationJson);
        Matcher bi = Regex.BUILD_ID.matcher(stationJson);

        while (rc.find() && bi.find()) {
            buildId = rc.group(1);
            buildIncremental = bi.group(1);
        }

        String securityPatch = null;
        String canaryId = null;

        try {
            Matcher canaryMatcher = Regex.IS_CANARY_PATTERN.matcher(stationJson);
            String lastCanaryObject = null;
            while (canaryMatcher.find()) {
                lastCanaryObject = canaryMatcher.group();
            }

            if (lastCanaryObject == null) {
                System.out.println("No canary object found!");
            }

            Matcher idMatcher = Regex.CANARY_ID.matcher(lastCanaryObject);
            if (idMatcher.find()) {
                canaryId = idMatcher.group(1);
            }
            if (canaryId != null && canaryId.length() >= 4) {
            canaryId = canaryId.substring(0, 4) + "-" +
                    canaryId.substring(4);
            }

            String secHtml = getHTML(
                PIXEL_SEC_BULLETIN_URL,
                null
        );

        Pattern sp = Pattern.compile(
                String.format(Regex.SECURITY_PATCH.pattern(),
                        Pattern.quote(canaryId)),
                Pattern.CASE_INSENSITIVE
        );

        Matcher sm = sp.matcher(secHtml);
        securityPatch = sm.find() ? sm.group(1) : null;
        } catch (Exception e) {
            canaryId = null;
        }

        if (securityPatch == null && canaryId != null) {
            securityPatch = canaryId + "-05";
        }

        String buildFingerprint =
                "google/" + product + "/" + device
                        + ":CANARY/" + buildId + "/" + buildIncremental
                        + ":user/release-keys";

        
        buildMeta.put("MODEL", deviceName);
        buildMeta.put("ID", buildId);
        buildMeta.put("BRAND", "google");
        buildMeta.put("PRODUCT", product);
        buildMeta.put("DEVICE", device);
        buildMeta.put("MANUFACTURER", "Google");
        buildMeta.put("INCREMENTAL", buildIncremental);
        buildMeta.put("TAGS", "user");
        buildMeta.put("TYPE", "release-keys");
        buildMeta.put("FINGERPRINT", buildFingerprint);
        buildMeta.put("SECURITY_PATCH", securityPatch);
    }

    public static List<String> getDevices() throws Exception {
        getLatestCanary();
        getFactoryImageInformation();
        parsePixelFiDevices();

    return new ArrayList<String>(betaDevices.keySet());

    }

    public static <T> T randomFromList(List<T> list) {
        if (list == null || list.isEmpty()) {
            throw new IllegalArgumentException("List is empty");
        }
        int index = ThreadLocalRandom.current().nextInt(list.size());
        return list.get(index);
    }

    private static <T> T pickRandomExcluding(List<T> list, T exclude) {
        if (list == null || list.size() < 2) return null;

        T chosen;
        do {
            chosen = list.get(
                    ThreadLocalRandom.current().nextInt(list.size()));
        } while (Objects.equals(chosen, exclude));

        return chosen;
    }

    public static Map<String, String> getFingerprint(String device) throws Exception {
        getBuildInfo(device);
        return buildMeta;
    }

    public static Map<String, String> getRandomFingerprint(String exclude) throws Exception {
        getBuildInfo(pickRandomExcluding(getDevices(), exclude));
        return buildMeta;
    }

    public static Map<String, String> getRandomFingerprint() throws Exception {
        getBuildInfo(randomFromList(getDevices()));
        return buildMeta;
    }

}
