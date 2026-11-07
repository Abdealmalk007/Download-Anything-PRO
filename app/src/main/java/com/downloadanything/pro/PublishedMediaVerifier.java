package com.downloadanything.pro;

import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Verification of the public MediaStore/FileProvider copy.
 *
 * 4.8.3 keeps strict track/duration parsing when the OEM provider supports it,
 * but can safely fall back to the byte-copy contract recorded by MediaPublisher.
 * This prevents a valid downloaded file from being discarded at 99% only because
 * MediaExtractor cannot parse a pending/recent MediaStore URI on a specific OEM.
 */
public final class PublishedMediaVerifier {
    private static final long MIN_MEDIA_BYTES = 512L;

    private PublishedMediaVerifier() {}

    public static boolean verify(Context c, String rawUri, boolean audioOnly) {
        if (verifyStrict(c,rawUri,audioOnly,false)) return true;
        Uri uri=parse(rawUri);
        if(uri==null||!MediaPublisher.isVerifiedPublished(c,uri))return false;
        // Readability is diagnostic here, not a hard codec/parser gate. MediaPublisher
        // records this URI only after strict source verification, exact OutputStream copy
        // and successful MediaStore finalization.
        verifyReadableCopy(c,rawUri,audioOnly,-1L);
        return audioOnly ? MediaPublisher.verifiedPublishedHasAudio(c,uri)
                : MediaPublisher.verifiedPublishedHasVideo(c,uri);
    }

    /** Verify the public copy against the job contract, including required social-video audio. */
    public static boolean verify(Context c, String rawUri, DownloadJob job) {
        boolean audioOnly = job != null && job.audio;
        boolean requireAudioWithVideo = job != null && MediaIntegrityPolicy.requiresVideoAudio(job);
        if(verifyStrict(c,rawUri,audioOnly,requireAudioWithVideo))return true;

        Uri uri=parse(rawUri);
        if(uri==null||!MediaPublisher.isVerifiedPublished(c,uri))return false;
        verifyReadableCopy(c,rawUri,audioOnly,-1L);
        if(audioOnly)return MediaPublisher.verifiedPublishedHasAudio(c,uri);
        if(!MediaPublisher.verifiedPublishedHasVideo(c,uri))return false;
        return !requireAudioWithVideo || MediaPublisher.verifiedPublishedHasAudio(c,uri);
    }

    /**
     * Lightweight public-copy gate. It validates readability, family, header and byte size
     * without depending on codec/container parsing of a pending MediaStore row.
     */
    public static boolean verifyReadableCopy(Context c,String rawUri,boolean audioOnly,long expectedBytes){
        if(c==null||rawUri==null||rawUri.trim().isEmpty())return false;
        Uri uri=parse(rawUri);if(uri==null)return false;
        try{
            String type=c.getContentResolver().getType(uri);
            if(type!=null&&!type.trim().isEmpty()){
                type=type.toLowerCase(Locale.ROOT);
                if(audioOnly&&type.startsWith("video/"))return false;
                if(!audioOnly&&type.startsWith("audio/"))return false;
            }

            try(ParcelFileDescriptor pfd=c.getContentResolver().openFileDescriptor(uri,"r")){
                if(pfd==null)return false;
                long statSize=pfd.getStatSize();
                if(statSize>=0L&&statSize<MIN_MEDIA_BYTES)return false;
                if(expectedBytes>0L&&statSize>=0L&&statSize!=expectedBytes)return false;
            }

            try(InputStream in=c.getContentResolver().openInputStream(uri)){
                if(in==null)return false;
                byte[] head=new byte[64];int n=in.read(head);if(n<=0)return false;
                String prefix=new String(head,0,n,StandardCharsets.ISO_8859_1).trim().toLowerCase(Locale.ROOT);
                if(prefix.startsWith("<html")||prefix.startsWith("<!doctype")||prefix.startsWith("{")||prefix.startsWith("["))return false;
            }
            return true;
        }catch(Exception e){return false;}
    }

    private static boolean verifyStrict(Context c, String rawUri, boolean audioOnly, boolean requireAudioWithVideo) {
        if (c == null || rawUri == null || rawUri.trim().isEmpty()) return false;
        Uri uri=parse(rawUri);if(uri==null)return false;

        try {
            String type = c.getContentResolver().getType(uri);
            if (type != null && !type.isEmpty()) {
                if (audioOnly && !type.startsWith("audio/")) return false;
                if (!audioOnly && !type.startsWith("video/")) return false;
            }

            try (InputStream in = c.getContentResolver().openInputStream(uri)) {
                if (in == null) return false;
                byte[] head = new byte[64];
                int n = in.read(head);
                if (n <= 0) return false;
                String prefix = new String(head, 0, n, StandardCharsets.ISO_8859_1)
                        .trim().toLowerCase(Locale.ROOT);
                if (prefix.startsWith("<html") || prefix.startsWith("<!doctype") || prefix.startsWith("{") || prefix.startsWith("[")) return false;
            }

            boolean hasVideo = false;
            boolean hasAudio = false;
            boolean videoHasSample = false;
            boolean audioHasSample = false;
            long durationUs = 0L;

            try (ParcelFileDescriptor pfd = c.getContentResolver().openFileDescriptor(uri, "r")) {
                if (pfd == null) return false;
                long statSize = pfd.getStatSize();
                if (statSize >= 0L && statSize < MIN_MEDIA_BYTES) return false;

                MediaExtractor extractor = new MediaExtractor();
                try {
                    extractor.setDataSource(pfd.getFileDescriptor());
                    int tracks = extractor.getTrackCount();
                    if (tracks <= 0) return false;
                    for (int i = 0; i < tracks; i++) {
                        MediaFormat format = extractor.getTrackFormat(i);
                        String mime = format.containsKey(MediaFormat.KEY_MIME) ? format.getString(MediaFormat.KEY_MIME) : "";
                        boolean isVideo = mime != null && mime.startsWith("video/");
                        boolean isAudio = mime != null && mime.startsWith("audio/");
                        if (!isVideo && !isAudio) continue;

                        extractor.selectTrack(i);
                        // Negative timestamps are valid for AAC encoder delay/priming.
                        // -1L is MediaExtractor's explicit end-of-stream sentinel.
                        boolean hasSample = extractor.getSampleTime() != -1L;
                        extractor.unselectTrack(i);

                        if (isVideo) { hasVideo = true; videoHasSample |= hasSample; }
                        if (isAudio) { hasAudio = true; audioHasSample |= hasSample; }
                        if (format.containsKey(MediaFormat.KEY_DURATION)) {
                            try { durationUs = Math.max(durationUs, format.getLong(MediaFormat.KEY_DURATION)); }
                            catch (Exception ignored) {}
                        }
                    }
                } finally {
                    try { extractor.release(); } catch (Exception ignored) {}
                }
            }

            if (durationUs <= 0L) {
                MediaMetadataRetriever metadata = new MediaMetadataRetriever();
                try {
                    metadata.setDataSource(c, uri);
                    String durationMs = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                    if (durationMs != null && !durationMs.trim().isEmpty()) {
                        durationUs = Math.max(0L, Long.parseLong(durationMs.trim()) * 1000L);
                    }
                } catch (Exception ignored) {
                    durationUs = 0L;
                } finally {
                    try { metadata.release(); } catch (Exception ignored) {}
                }
            }

            if (audioOnly) {
                if (!hasAudio || !audioHasSample) return false;
            } else {
                if (!hasVideo || !videoHasSample) return false;
                if (requireAudioWithVideo && (!hasAudio || !audioHasSample)) return false;
            }
            return durationUs > 0L;
        } catch (Exception e) {
            return false;
        }
    }

    private static Uri parse(String raw){
        try{return raw==null?null:Uri.parse(raw);}catch(Exception e){return null;}
    }
}
