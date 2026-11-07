package com.downloadanything.pro;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Central video-quality policy introduced in 4.7.2. */
public final class VideoQualityPolicy {
    public static final int MEDIUM_TARGET_HEIGHT = 720;

    private VideoQualityPolicy() {}

    /** Returns unique, valid source heights sorted highest to lowest. */
    public static List<Integer> normalizeHeights(Iterable<Integer> input) {
        Set<Integer> unique = new LinkedHashSet<>();
        if (input != null) {
            for (Integer h : input) if (h != null && h > 0 && h <= 32768) unique.add(h);
        }
        ArrayList<Integer> out = new ArrayList<>(unique);
        out.sort(Collections.reverseOrder());
        return out;
    }

    /**
     * Medium means 720p when available. Otherwise choose the nearest lower
     * source height; if the source only exposes higher resolutions, choose the
     * smallest available higher resolution.
     */
    public static int mediumIndex(List<Integer> descendingHeights) {
        if (descendingHeights == null || descendingHeights.isEmpty()) return 0;
        int chosen = 0;
        int bestDistance = Integer.MAX_VALUE;
        for (int i = 0; i < descendingHeights.size(); i++) {
            int h = descendingHeights.get(i);
            int distance = h <= MEDIUM_TARGET_HEIGHT
                    ? MEDIUM_TARGET_HEIGHT - h
                    : 10000 + (h - MEDIUM_TARGET_HEIGHT);
            if (distance < bestDistance) { bestDistance = distance; chosen = i; }
        }
        return chosen;
    }

    public static String label(int h) {
        if (h == 4320) return "8K • 4320p";
        if (h == 2160) return "4K • 2160p";
        if (h == 1440) return "2K • 1440p";
        if (h == 1080) return "1080p • FHD";
        if (h == 720) return "720p • HD";
        if (h == 480) return "480p • SD";
        return h + "p";
    }
}
