package com.downloadanything.pro;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

/** Read-only cookie-file health check used by diagnostics and request setup. */
public final class CookieHealth {
    private CookieHealth() {}

    public static boolean isUsable(String path) {
        if (path == null || path.trim().isEmpty()) return false;
        File f = new File(path);
        if (!f.isFile() || f.length() <= 0L) return false;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            int inspected = 0;
            while ((line = r.readLine()) != null && inspected++ < 200) {
                String x = line.trim();
                if (x.isEmpty() || x.startsWith("#")) continue;
                // Netscape cookie files contain seven tab-separated fields.
                if (x.split("\\t").length >= 7 || x.startsWith("Cookie:") || x.contains("=")) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    public static String state(String path) {
        if (path == null || path.trim().isEmpty()) return "none";
        return isUsable(path) ? "usable" : "invalid_or_empty";
    }
}
