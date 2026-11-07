package com.downloadanything.pro;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Direct TikTok CDN downloader used after the real WebView player exposes a media URL.
 * It avoids sending an already-resolved CDN URL back through the TikTok extractor.
 */
public final class TikTokDirectDownloader {
    private static final Map<String, AtomicBoolean> CANCEL = new ConcurrentHashMap<>();
    private static final int BUFFER = 512 * 1024;

    private TikTokDirectDownloader() {}

    public static String download(Context context, DownloadJob job, String processId,
                                  TikTokBrowserFallback.Result result,
                                  DownloadEngine.ProgressCallback cb, long started) throws Exception {
        try {
            File complete=executeDownload(context,job,processId,result,cb,started);
            DownloadControl.check(processId);
            cb.progress(99f,0L,"",complete.length(),complete.length(),"publishing");
            String uri=MediaPublisher.publishFinal(context,complete.getParentFile(),complete,false);
            if (uri==null || uri.trim().isEmpty()) throw new IOException("TikTok download completed but publishing failed");
            return uri;
        }
        finally { CANCEL.remove(processId); }
    }

    /** Private intermediate only. Audio recovery publishes once, after local extraction. */
    static File downloadAudioSource(Context context, DownloadJob job, String processId,
                                    TikTokBrowserFallback.Result result,
                                    DownloadEngine.ProgressCallback cb, long started) throws Exception {
        if (!job.audio || result==null || !new TikTokAudioRecovery.Candidate(
                result.verifiedAudioSourceId,result.pageUrl,result.mediaUrl,result.cookies,result.userAgent)
                .matches(job.sourceId,job.resolvedUrl))
            throw new IOException("TikTok audio source identity mismatch");
        try { return executeDownload(context,job,processId,result,cb,started); }
        finally { CANCEL.remove(processId); }
    }

    private static File executeDownload(Context context, DownloadJob job, String processId,
                                          TikTokBrowserFallback.Result result,
                                          DownloadEngine.ProgressCallback cb, long started) throws Exception {
        DownloadControl.check(processId);
        if (context == null || result == null || !result.validFor(job.resolvedUrl)) throw new IOException("TikTok direct media URL unavailable");
        AtomicBoolean cancelled = new AtomicBoolean(false);
        CANCEL.put(processId, cancelled);
        File work = MediaPublisher.jobWorkDir(context, job.id);
        if (!work.exists() && !work.mkdirs()) throw new IOException("Unable to create download directory");

        String videoId = TikTokBrowserFallback.extractVideoId(result.pageUrl);
        if (videoId.isEmpty()) videoId = TikTokBrowserFallback.extractVideoId(job.url);
        String base = FilenameRules.safeBase(job.title);
        if (base.isEmpty() || "TikTok".equalsIgnoreCase(base)) base = "TikTok";
        String suffix = videoId.isEmpty() ? Long.toString(System.currentTimeMillis()) : videoId;
        String orderedBase = FilenameRules.batchPrefix(job) + base;
        if (job.audio) {
            // A refreshed CDN representation must never append to bytes from an older URL.
            byte[] digest=java.security.MessageDigest.getInstance("SHA-256")
                    .digest(result.mediaUrl.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hash=new StringBuilder();
            for(byte b:digest)hash.append(String.format(Locale.ROOT,"%02x",b&255));
            orderedBase="audio-source-"+hash;
        }
        File part = new File(work, orderedBase + " [" + suffix + "].mp4.part");
        File complete = new File(work, orderedBase + " [" + suffix + "].mp4");
        if (complete.exists()) complete = new File(work, base + " [" + suffix + "-" + System.currentTimeMillis() + "].mp4");

        long existing = part.exists() ? Math.max(0L, part.length()) : 0L;
        HttpURLConnection c = job.audio ? openSecure(result,existing) : open(result, existing);
        int code = c.getResponseCode();
        if (existing > 0L && code == 416) {
            c.disconnect();
            part.delete();
            existing = 0L;
            c = job.audio ? openSecure(result,0L) : open(result, 0L);
            code = c.getResponseCode();
        }
        if (code != 200 && code != 206) {
            String msg = "TikTok CDN HTTP " + code;
            c.disconnect();
            throw new IOException(msg);
        }
        if(code==206){
            String range=c.getHeaderField("Content-Range");
            if(range==null || !range.startsWith("bytes "+existing+"-")){c.disconnect();throw new IOException("Invalid CDN Content-Range");}
        }
        String mime=c.getContentType();
        if(mime!=null && (mime.startsWith("image/") || mime.contains("text/html") || mime.contains("mpegurl") || mime.contains("dash+xml"))){c.disconnect();throw new IOException("CDN did not return a final video");}
        if (existing > 0L && code != 206) {
            existing = 0L;
            if (part.exists()) part.delete();
        }

        long total = contentTotal(c, existing);
        long downloaded = existing;
        long lastAt = System.currentTimeMillis();
        long lastBytes = downloaded;
        boolean append = existing > 0L && code == 206;
        cb.progress(total > 0 ? (downloaded * 100f / total) : 0f, 0L, "", downloaded, total, "tiktok_direct");

        try (InputStream in = new BufferedInputStream(c.getInputStream(), BUFFER);
             BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(part, append), BUFFER)) {
            byte[] buf = new byte[BUFFER];
            int n;
            while ((n = in.read(buf)) >= 0) {
                DownloadControl.check(processId);
                if (cancelled.get()) throw new IOException("Download cancelled");
                if (n == 0) continue;
                out.write(buf, 0, n);
                downloaded += n;
                long now = System.currentTimeMillis();
                if (now - lastAt >= 250L) {
                    long deltaBytes = downloaded - lastBytes;
                    long deltaMs = Math.max(1L, now - lastAt);
                    double bytesPerSec = deltaBytes * 1000d / deltaMs;
                    float pct = total > 0 ? Math.min(99.9f, downloaded * 100f / total) : 0f;
                    long eta = total > downloaded && bytesPerSec > 1 ? Math.max(0L, Math.round((total - downloaded) / bytesPerSec)) : 0L;
                    cb.progress(pct, eta, formatSpeed(bytesPerSec), downloaded, total, "tiktok_direct");
                    lastAt = now;
                    lastBytes = downloaded;
                }
            }
            out.flush();
        } finally {
            c.disconnect();
            CANCEL.remove(processId);
        }

        DownloadControl.check(processId);
        if (cancelled.get()) throw new IOException("Download cancelled");
        if(total>0 && downloaded!=total) throw new IOException("Incomplete CDN response");
        if (complete.exists() && !complete.delete()) throw new IOException("Unable to replace completed file");
        if (!part.renameTo(complete)) {
            copyRename(part, complete);
        }
        complete.setLastModified(System.currentTimeMillis());
        cb.progress(99f, 0L, "", complete.length(), complete.length(), "verifying");
        MediaVerifier.Result verified=MediaVerifier.verify(complete,false);
        if(!verified.valid){complete.delete();throw new IOException("Media verification failed: "+verified.reason);}
        if((job.audio || MediaIntegrityPolicy.requiresVideoAudio(job)) && !verified.hasAudio){
            complete.delete();
            throw new IOException("TikTok direct stream is missing its audio track");
        }
        if(!job.audio && job.quality!=null && job.quality.matches("[1-9][0-9]*")){
            android.media.MediaMetadataRetriever metadata=new android.media.MediaMetadataRetriever();
            try {
                metadata.setDataSource(complete.getAbsolutePath());
                String height=metadata.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
                if(height!=null && !height.trim().isEmpty() && !job.quality.equals(height.trim()))
                    throw new IOException("CDN stream does not match selected height");
            } finally {metadata.release();}
        }
        DownloadControl.check(processId);
        return complete;
    }

    /**
     * Pre-transfer probe used by 5.3.1 before committing to the direct CDN strategy.
     * It deliberately transfers no media body: redirects/headers/status are enough to
     * reject stale HTML/image/manifest URLs before the one real transfer attempt.
     */
    public static boolean preflight(TikTokBrowserFallback.Result r) {
        return preflight(r,false);
    }

    static boolean preflightAudio(TikTokBrowserFallback.Result r) {
        return preflight(r,true);
    }

    private static boolean preflight(TikTokBrowserFallback.Result r, boolean secure) {
        if (r == null || blank(r.mediaUrl)) return false;
        String u = r.mediaUrl.trim().toLowerCase(Locale.ROOT);
        if (u.startsWith("blob:") || u.startsWith("data:") || u.endsWith(".m3u8") || u.contains(".m3u8?")
                || u.endsWith(".mpd") || u.contains(".mpd?")) return false;
        HttpURLConnection c = null;
        try {
            c = secure ? openSecure(r,-1L) : open(r, 0L);
            if (!secure) c.setRequestProperty("Range", "bytes=0-1");
            int code = c.getResponseCode();
            if (code != 200 && code != 206) return false;
            String mime = c.getContentType();
            String m = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
            if (m.startsWith("image/") || m.contains("text/html") || m.contains("json")
                    || m.contains("mpegurl") || m.contains("dash+xml")) return false;
            if (m.startsWith("video/")) return true;
            // Some TikTok CDNs omit a specific MIME type. Accept opaque binary only
            // when the final redirected URL still looks like a media/CDN resource.
            String finalUrl = c.getURL() == null ? "" : c.getURL().toString().toLowerCase(Locale.ROOT);
            return (m.isEmpty() || m.contains("octet-stream"))
                    && (finalUrl.contains("tiktok") || finalUrl.contains("byte") || finalUrl.contains("video"));
        } catch (Exception ignored) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    public static void cancel(String processId) {
        if (processId == null) return;
        AtomicBoolean f = CANCEL.get(processId);
        if (f != null) f.set(true);
    }

    private static HttpURLConnection open(TikTokBrowserFallback.Result r, long offset) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(r.mediaUrl).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(10_000);
        c.setReadTimeout(30_000);
        c.setRequestProperty("User-Agent", blank(r.userAgent) ? "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 Chrome/140 Mobile Safari/537.36" : r.userAgent);
        c.setRequestProperty("Accept", "*/*");
        c.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
        c.setRequestProperty("Accept-Encoding", "identity");
        c.setRequestProperty("Connection", "keep-alive");
        c.setRequestProperty("Origin", "https://www.tiktok.com");
        c.setRequestProperty("Sec-Fetch-Dest", "video");
        c.setRequestProperty("Sec-Fetch-Mode", "no-cors");
        c.setRequestProperty("Sec-Fetch-Site", "cross-site");
        c.setRequestProperty("Referer", blank(r.pageUrl) ? "https://www.tiktok.com/" : r.pageUrl);
        if (!blank(r.cookies)) c.setRequestProperty("Cookie", r.cookies);
        if (offset > 0L) c.setRequestProperty("Range", "bytes=" + offset + "-");
        return c;
    }

    /** Android's default HTTPS verifier remains in force at every redirect hop. */
    private static HttpURLConnection openSecure(TikTokBrowserFallback.Result r,long offset) throws Exception {
        String url=r.mediaUrl;
        for(int redirects=0;redirects<=5;redirects++){
            if(!TikTokAudioRecovery.secureMedia(url))
                throw new IOException("TikTok audio refused an untrusted or non-HTTPS media redirect");
            HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
            try {
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(10_000);
                c.setReadTimeout(30_000);
                c.setRequestProperty("User-Agent",blank(r.userAgent) ? "Mozilla/5.0" : r.userAgent);
                c.setRequestProperty("Accept","*/*");
                c.setRequestProperty("Accept-Encoding","identity");
                c.setRequestProperty("Referer",r.pageUrl);
                c.setRequestProperty("Origin","https://www.tiktok.com");
                // Page cookies belong to tiktok.com, not arbitrary CDN redirect hosts.
                if(offset<0L)c.setRequestProperty("Range","bytes=0-1");
                else if(offset>0L)c.setRequestProperty("Range","bytes="+offset+"-");
                int code=c.getResponseCode();
                if(code!=301 && code!=302 && code!=303 && code!=307 && code!=308)return c;
                String next=c.getHeaderField("Location");
                if(blank(next))throw new IOException("TikTok CDN redirect has no location");
                url=new URL(new URL(url),next).toString();
            }catch(Exception error){c.disconnect();throw error;}
            c.disconnect();
        }
        throw new IOException("TikTok CDN redirect limit exceeded");
    }

    private static long contentTotal(HttpURLConnection c, long existing) {
        try {
            String range = c.getHeaderField("Content-Range");
            if (range != null) {
                int slash = range.lastIndexOf('/');
                if (slash >= 0) return Long.parseLong(range.substring(slash + 1).trim());
            }
        } catch (Exception ignored) {}
        try {
            long remaining = c.getContentLengthLong();
            return remaining > 0 ? existing + remaining : 0L;
        } catch (Exception e) { return 0L; }
    }

    private static String formatSpeed(double bps) {
        if (bps >= 1024d * 1024d) return String.format(Locale.US, "%.1f MiB/s", bps / 1024d / 1024d);
        if (bps >= 1024d) return String.format(Locale.US, "%.0f KiB/s", bps / 1024d);
        return String.format(Locale.US, "%.0f B/s", bps);
    }

    private static String safeName(String s) {
        if (s == null) return "";
        String x = s.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
        if (x.length() > 80) x = x.substring(0, 80).trim();
        return x;
    }

    private static boolean blank(String s) { return s == null || s.trim().isEmpty(); }

    private static void copyRename(File src, File dst) throws IOException {
        try (InputStream in = new java.io.FileInputStream(src);
             BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(dst), BUFFER)) {
            byte[] b = new byte[BUFFER]; int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            out.flush();
        }
        if (!src.delete()) src.deleteOnExit();
    }
}
