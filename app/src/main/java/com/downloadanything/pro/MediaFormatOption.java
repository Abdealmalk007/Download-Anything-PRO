package com.downloadanything.pro;

import java.util.Locale;

/** Immutable source-reported media format used by the 4.7.5 format picker. */
public final class MediaFormatOption {
    public final String id;
    public final String ext;
    public final String vcodec;
    public final String acodec;
    public final int height;
    public final int width;
    public final double fps;
    public final double abr;
    public final double tbr;
    public final long fileSize;
    public final boolean video;
    public final boolean audio;

    public MediaFormatOption(String id, String ext, String vcodec, String acodec,
                             int height, int width, double fps, double abr, double tbr,
                             long fileSize, boolean video, boolean audio) {
        this.id = clean(id);
        this.ext = clean(ext);
        this.vcodec = clean(vcodec);
        this.acodec = clean(acodec);
        this.height = Math.max(0, height);
        this.width = Math.max(0, width);
        this.fps = Math.max(0d, fps);
        this.abr = Math.max(0d, abr);
        this.tbr = Math.max(0d, tbr);
        this.fileSize = Math.max(0L, fileSize);
        this.video = video;
        this.audio = audio;
    }

    public boolean progressive() { return video && audio; }

    /** A compact, locale-stable technical label for UI/tests. */
    public String technicalLabel() {
        StringBuilder b = new StringBuilder();
        if (video) {
            if (height > 0) b.append(height).append('p');
            else b.append("Video");
            if (fps > 0) b.append(" • ").append(trim(fps)).append("fps");
            if (!ext.isEmpty()) b.append(" • ").append(ext.toUpperCase(Locale.ROOT));
            if (!vcodec.isEmpty() && !"none".equalsIgnoreCase(vcodec)) b.append(" • ").append(shortCodec(vcodec));
            if (progressive()) b.append(" • A/V");
        } else if (audio) {
            b.append("Audio");
            if (abr > 0) b.append(" • ").append(Math.round(abr)).append("kbps");
            if (!ext.isEmpty()) b.append(" • ").append(ext.toUpperCase(Locale.ROOT));
            if (!acodec.isEmpty() && !"none".equalsIgnoreCase(acodec)) b.append(" • ").append(shortCodec(acodec));
        }
        if (fileSize > 0) b.append(" • ").append(formatBytes(fileSize));
        return b.toString();
    }

    public static String formatBytes(long bytes) {
        if (bytes <= 0) return "";
        double value = bytes;
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int unit = 0;
        while (value >= 1024d && unit < units.length - 1) { value /= 1024d; unit++; }
        if (unit == 0) return ((long)value) + " " + units[unit];
        return String.format(Locale.ROOT, value >= 100 ? "%.0f %s" : value >= 10 ? "%.1f %s" : "%.2f %s", value, units[unit]);
    }

    private static String clean(String s) { return s == null ? "" : s.trim(); }
    private static String trim(double n) { return n == Math.rint(n) ? String.valueOf((long)n) : String.format(Locale.ROOT, "%.1f", n); }
    private static String shortCodec(String s) {
        String x = clean(s);
        int dot = x.indexOf('.');
        return dot > 0 ? x.substring(0, dot) : x;
    }
}
