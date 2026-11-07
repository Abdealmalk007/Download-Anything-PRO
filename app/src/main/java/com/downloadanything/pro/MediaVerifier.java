package com.downloadanything.pro;

import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;

import java.io.File;
import java.util.Locale;

/**
 * Final-media verification used before anything is exposed to MediaStore.
 * 3.1.3 deliberately avoids treating decoder discovery as a hard failure:
 * OEM MediaCodecList results can be incomplete for files that Android players
 * can still open. The hard gates are a parseable container, real tracks,
 * samples and a positive duration.
 */
public final class MediaVerifier {
    private static final long MIN_MEDIA_BYTES = 512L;

    public static final class Result {
        public final boolean valid;
        public final boolean hasVideo;
        public final boolean hasAudio;
        public final long durationUs;
        public final String reason;

        Result(boolean valid, boolean hasVideo, boolean hasAudio, long durationUs, String reason) {
            this.valid = valid;
            this.hasVideo = hasVideo;
            this.hasAudio = hasAudio;
            this.durationUs = durationUs;
            this.reason = reason == null ? "" : reason;
        }
    }

    private MediaVerifier() {}

    /** Best-effort MIME detection; never reject an otherwise verified file only because metadata MIME is absent. */
    public static String detectedMime(File file) {
        if (file == null) return "";
        MediaMetadataRetriever metadata = new MediaMetadataRetriever();
        try {
            metadata.setDataSource(file.getAbsolutePath());
            String mime = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE);
            if (mime != null && !mime.trim().isEmpty()) return mime.toLowerCase(Locale.ROOT);
        } catch (Exception ignored) {
        } finally {
            try { metadata.release(); } catch (Exception ignored) {}
        }
        return mimeFromName(file.getName());
    }

    /**
     * Audio jobs require a real audio track. Video jobs require a real video
     * track; audio is accepted when present but is not mandatory because silent
     * source videos are valid media and must not fail at 99%.
     */
    public static Result verify(File file, boolean expectAudioOnly) {
        if (file == null || !file.isFile()) return bad("missing file");
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (!isFinalMediaName(name)) return bad("not a final media file");
        if (file.length() < MIN_MEDIA_BYTES) return bad("file is too small");

        MediaExtractor extractor = new MediaExtractor();
        boolean video = false;
        boolean audio = false;
        long durationUs = 0L;
        boolean videoHasSample = false;
        boolean audioHasSample = false;
        try {
            extractor.setDataSource(file.getAbsolutePath());
            int tracks = extractor.getTrackCount();
            if (tracks <= 0) return fallbackVerifiedVideoFrame(file, expectAudioOnly, "no media tracks");
            for (int i = 0; i < tracks; i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.containsKey(MediaFormat.KEY_MIME) ? format.getString(MediaFormat.KEY_MIME) : "";
                boolean isVideo = mime != null && mime.startsWith("video/");
                boolean isAudio = mime != null && mime.startsWith("audio/");
                if (!isVideo && !isAudio) continue;

                extractor.selectTrack(i);
                // AAC priming samples may have NEGATIVE presentation timestamps.
                // MediaExtractor returns exactly -1 only when no sample is available.
                // A valid sample at -23219us must not be rejected as empty media.
                boolean hasSample = extractor.getSampleTime() != -1L;
                extractor.unselectTrack(i);
                if (isVideo) { video = true; videoHasSample |= hasSample; }
                if (isAudio) { audio = true; audioHasSample |= hasSample; }

                if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    try { durationUs = Math.max(durationUs, format.getLong(MediaFormat.KEY_DURATION)); }
                    catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            return fallbackVerifiedVideoFrame(file, expectAudioOnly,
                    "container cannot be parsed: " + e.getClass().getSimpleName());
        } finally {
            try { extractor.release(); } catch (Exception ignored) {}
        }

        // Some valid containers do not expose KEY_DURATION on every track.
        if (durationUs <= 0L) durationUs = metadataDurationUs(file);

        if (expectAudioOnly) {
            if (!audio) return new Result(false, video, false, durationUs, "audio track missing");
            if (!audioHasSample) return new Result(false, video, true, durationUs, "audio track has no samples");
        } else {
            if (!video) return fallbackVerifiedVideoFrame(file, false, "video track missing");
            if (!videoHasSample) return fallbackVerifiedVideoFrame(file, false, "video track has no samples");
        }
        if (durationUs <= 0L) return fallbackVerifiedVideoFrame(file, expectAudioOnly,
                "media duration must be positive");
        return new Result(true, video, audio, durationUs, "ok");
    }

    /**
     * API30 MediaExtractor may report zero readable samples for genuine VP9-in-MP4
     * videos even though MediaMetadataRetriever can decode an actual video frame.
     * Only video files can use this independent fallback. Never trust an extension
     * or duration alone: demand recognized video track metadata, a decoded frame
     * and positive duration; report audio metadata separately for social guardrails.
     */
    private static Result fallbackVerifiedVideoFrame(File file, boolean audioOnly, String primaryReason) {
        if (audioOnly || file == null || !file.isFile() || file.length() < MIN_MEDIA_BYTES)
            return bad(primaryReason);
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        android.graphics.Bitmap frame = null;
        try {
            retriever.setDataSource(file.getAbsolutePath());
            String hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO);
            String hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO);
            String millis = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            long durationMs = millis == null ? 0 : Long.parseLong(millis.trim());
            if (!"yes".equalsIgnoreCase(hasVideo) || durationMs <= 0)
                return bad(primaryReason + "; fallback metadata lacks real video/duration");
            frame = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null || frame.getWidth() <= 0 || frame.getHeight() <= 0)
                return bad(primaryReason + "; no decodable video frame");
            return new Result(true, true, "yes".equalsIgnoreCase(hasAudio),
                    durationMs * 1000L, "verified metadata + decoded video frame");
        } catch (Exception e) {
            return bad(primaryReason + "; fallback: " + e.getClass().getSimpleName());
        } finally {
            if (frame != null) frame.recycle();
            try { retriever.release(); } catch (Exception ignored) {}
        }
    }

    public static boolean isFinalMediaName(String rawName) {
        if (rawName == null) return false;
        String n = rawName.toLowerCase(Locale.ROOT);
        if (n.endsWith(".part") || n.endsWith(".ytdl") || n.endsWith(".temp") || n.endsWith(".tmp")) return false;
        if (n.matches(".*\\.f[^.]+\\.(mp4|m4a|webm|mkv|mov|3gp|aac|opus|ogg)$")) return false;
        if (n.contains(".part-frag") || n.contains(".frag") || n.endsWith(".m4s") || n.endsWith(".ts")) return false;
        return n.endsWith(".mp4") || n.endsWith(".mkv") || n.endsWith(".webm") || n.endsWith(".mov") ||
                n.endsWith(".3gp") || n.endsWith(".mp3") || n.endsWith(".m4a") || n.endsWith(".opus") ||
                n.endsWith(".aac") || n.endsWith(".flac") || n.endsWith(".wav") || n.endsWith(".ogg");
    }

    private static long metadataDurationUs(File file) {
        MediaMetadataRetriever metadata = new MediaMetadataRetriever();
        try {
            metadata.setDataSource(file.getAbsolutePath());
            String durationMs = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (durationMs == null || durationMs.trim().isEmpty()) return 0L;
            return Math.max(0L, Long.parseLong(durationMs.trim()) * 1000L);
        } catch (Exception ignored) {
            return 0L;
        } finally {
            try { metadata.release(); } catch (Exception ignored) {}
        }
    }

    private static String mimeFromName(String rawName) {
        String n = rawName == null ? "" : rawName.toLowerCase(Locale.ROOT);
        if (n.endsWith(".mp4")) return "video/mp4";
        if (n.endsWith(".mkv")) return "video/x-matroska";
        if (n.endsWith(".webm")) return "video/webm";
        if (n.endsWith(".mov")) return "video/quicktime";
        if (n.endsWith(".3gp")) return "video/3gpp";
        if (n.endsWith(".mp3")) return "audio/mpeg";
        if (n.endsWith(".m4a")) return "audio/mp4";
        if (n.endsWith(".aac")) return "audio/aac";
        if (n.endsWith(".opus") || n.endsWith(".ogg")) return "audio/ogg";
        if (n.endsWith(".flac")) return "audio/flac";
        if (n.endsWith(".wav")) return "audio/wav";
        return "";
    }

    private static Result bad(String reason) {
        return new Result(false, false, false, 0L, reason);
    }
}
