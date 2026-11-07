package com.downloadanything.pro;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.webkit.CookieManager;

import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import com.yausername.youtubedl_android.mapper.VideoInfo;

import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import kotlin.Unit;
import kotlin.jvm.functions.Function3;

public final class DownloadEngine {
    private static final ExecutorService INFO_EXEC = RuntimeExecutors.analysis();
    private static final ExecutorService DOWNLOAD_EXEC = RuntimeExecutors.download();
    private static final String TIKTOK_UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140 Mobile Safari/537.36";
    private static final String SOCIAL_UA = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36";
    private static final Pattern SPEED = Pattern.compile("(?:at|speed)\\s+([^\\s]+/s)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TOTAL = Pattern.compile("of\\s+~?\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(B|KiB|MiB|GiB|KB|MB|GB)", Pattern.CASE_INSENSITIVE);
    private static final int FACEBOOK_INFO_NETWORK_RETRIES = 1;

    public interface InfoCallback { void done(VideoInfo info, Exception error); }
    public interface ProgressCallback {
        void progress(float percent, long etaSeconds, String speed, long downloadedBytes, long totalBytes, String phase);
        void done(String outputUri, Exception error);
    }

    private DownloadEngine() {}

    public static File downloadDir(Context c) { return MediaPublisher.workDir(c); }

    public static void info(Context context, String url, String cookieFile, String userAgent, InfoCallback cb) {
        final String requestUrl = SocialUrlCanonicalizer.canonicalize(LinkCapture.normalize(url));
        if (LinkCapture.isAppStoreUrl(requestUrl)) {
            cb.done(null,new IOException("App store URL is not a media video link"));
            return;
        }
        if (!AnalysisWorkCoordinator.begin(requestUrl, cookieFile, userAgent, cb)) {
            PerformanceTracker.event(context, "analysis_singleflight_join:" + LinkCapture.platform(requestUrl));
            return;
        }
        try {
        INFO_EXEC.execute(() -> {
            String resolved = requestUrl;
            String autoCookie = "";
            String browserCookie = "";
            boolean deleteCookie = false;
            try {
                // The metadata path should not wait for a browser + redirect
                // chain before the first extractor call for regular vm/vt links.
                resolved = TikTokResolver.needsResolution(requestUrl) &&
                        !unresolvedTikTokLite(requestUrl)
                        ? requestUrl : TikTokLinkRecovery.resolveCanonical(context, requestUrl, 7_000L);
                if (TikTokResolver.isTikTok(requestUrl) && !TikTokResolver.isTikTok(resolved)) {
                    TikTokDiagnostics.record(context, "offsite_redirect_rejected", "store/non-TikTok landing");
                    resolved = requestUrl;
                }
                if (SocialUrlCanonicalizer.isFacebookShare(resolved))
                    PerformanceTracker.event(context,"facebook_share_engine_fallback_analysis");
                // The native yt-dlp Facebook extractor can resolve public /share/v/ links
                // itself. Do not abort merely because HTTP pre-resolution was blocked.
                if (TikTokResolver.isTikTok(requestUrl)) TikTokDiagnostics.record(context, "resolve_trace", TikTokResolver.lastTrace());
                boolean tikTok = TikTokResolver.isTikTok(resolved);
                if (tikTok && TikTokResolver.unresolvedShortLink(resolved)) {
                    if (unresolvedTikTokLite(resolved))
                        throw new IOException("TikTok short link could not be resolved to a video page: TikTok Lite OneLink");
                    TikTokDiagnostics.record(context, "short_url_engine_resolution", resolved);
                }
                // yt-dlp resolves ordinary vt/vm short URLs; unresolved TikTok Lite wrappers are blocked.
                autoCookie = effectiveCookieFile(context, resolved, cookieFile);
                deleteCookie = !blank(autoCookie) && !autoCookie.equals(cookieFile);
                if (tikTok) TikTokDiagnostics.request(context, "analysis", requestUrl, resolved, "yt-dlp-once", autoCookie);
                EngineRuntime.ensureYoutube(context);
                VideoInfo info;
                if (tikTok) {
                    // 5.6.7: fast-first extraction. Browser session is a slow
                    // recovery tool, not a prerequisite for every preview.
                    try {
                        info = runInfo(context, resolved, autoCookie, userAgent, true, false);
                        TikTokDiagnostics.record(context, "analysis_strategy", "fast_extractor");
                    } catch (Exception fastError) {
                        TikTokDiagnostics.record(context, "fast_extractor_fallback", fastError.toString());
                        String resolvedRetry=TikTokLinkRecovery.resolveCanonical(context,resolved,7_000L);
                        if (!TikTokResolver.isTikTok(resolvedRetry)) resolvedRetry=resolved;
                        String analysisCookie=autoCookie, analysisUa=userAgent;
                        try {
                            TikTokBrowserFallback.Result br=TikTokBrowserFallback.peekFresh(resolvedRetry,30_000L);
                            if (br==null || !br.validFor(resolvedRetry))
                                br=TikTokBrowserFallback.resolve(context,resolvedRetry,6_000L);
                            if (br!=null && br.validFor(resolvedRetry)) {
                                browserCookie=CookieBridge.writeCookieFile(context,resolvedRetry,br.cookies);
                                if (!blank(browserCookie)) analysisCookie=browserCookie;
                                if (!blank(br.userAgent)) analysisUa=br.userAgent;
                            }
                        } catch(Exception ignored) {}
                        try { info=runInfo(context,resolvedRetry,analysisCookie,analysisUa,true,false); }
                        catch(Exception retryError){ fastError.addSuppressed(retryError); throw fastError; }
                        resolved=resolvedRetry;
                    }
                } else {
                    try { info = runFacebookInfoWithRecovery(context, resolved, autoCookie, userAgent); }
                    catch (Exception parseFailure) {
                        // One carefully scoped Facebook fallback. Never treat
                        // unrelated media IDs as the user's requested video.
                        String alternate=FacebookSourceRecovery.alternateOnParseFailure(resolved,parseFailure);
                        if (alternate.isEmpty()) throw parseFailure;
                        try {
                            VideoInfo alternative=runInfo(context,alternate,autoCookie,userAgent,false,false);
                            if (!FacebookSourceRecovery.matchesExpected(alternate,infoId(alternative)))
                                throw new IOException("Facebook fallback source mismatch");
                            info=alternative;
                            PerformanceTracker.event(context,"facebook_same_id_watch_recovery");
                        } catch (Exception recoveryError) {
                            parseFailure.addSuppressed(recoveryError);
                            throw parseFailure;
                        }
                    }
                }
                if (tikTok) {
                    TikTokAudioRecovery.remember(info,resolved,"",blank(userAgent)?TIKTOK_UA:userAgent,
                            System.currentTimeMillis());
                    TikTokDiagnostics.success(context, "analysis", resolved, "yt-dlp-once");
                }
                AnalysisWorkCoordinator.complete(requestUrl, cookieFile, userAgent, info, null);
            } catch (Exception e) {
                if (TikTokResolver.isTikTok(requestUrl) || TikTokResolver.isTikTok(resolved))
                    TikTokDiagnostics.failure(context, "analysis", requestUrl, resolved, "yt-dlp-once", e);
                AnalysisWorkCoordinator.complete(requestUrl, cookieFile, userAgent, null, e);
            } finally {
                if (deleteCookie) CookieBridge.delete(autoCookie);
                CookieBridge.delete(browserCookie);
            }
        });
        } catch (RuntimeException dispatchError) {
            // A rejected task must release every joined caller. Otherwise the
            // process-local single-flight key would remain occupied indefinitely.
            PerformanceTracker.event(context, "analysis_dispatch_failed:" + LinkCapture.platform(requestUrl));
            AnalysisWorkCoordinator.complete(requestUrl, cookieFile, userAgent, null, dispatchError);
        }
    }

    private static VideoInfo runInfo(Context context, String url, String cookieFile, String userAgent, boolean tikTokFallback, boolean forceTikTokHeaders) throws Exception {
        // Python tempfile must point to the writable app sandbox even for metadata-only analysis.
        File tempDir = WritableTempGuard.configureYoutube(context);
        YoutubeDLRequest r = new YoutubeDLRequest(url);
        r.addOption("--cache-dir", new File(tempDir,"yt-cache").getAbsolutePath());
        applyAccessOptions(context, r, url, cookieFile, userAgent, tikTokFallback, forceTikTokHeaders);
        r.addOption("--no-playlist");
        r.addOption("--skip-download");
        r.addOption("--extractor-retries", tikTokFallback ? "0" : "1");
        r.addOption("--socket-timeout", tikTokFallback ? "10" : "12");
        r.addOption("--no-warnings");
        EngineUpdateManager.ENGINE_READ.lock();
        try {
            return YoutubeDL.getInstance().getInfo(r);
        } finally {
            EngineUpdateManager.ENGINE_READ.unlock();
        }
    }

    private static VideoInfo runFacebookInfoWithRecovery(Context context, String url,
                                                          String cookieFile, String userAgent) throws Exception {
        try {
            return runInfo(context,url,cookieFile,userAgent,false,false);
        } catch(Exception first) {
            if (!FacebookSourceRecovery.isFacebook(url) ||
                    RetryClassifier.classify(String.valueOf(first.getMessage())) != RetryClassifier.Kind.NETWORK) throw first;
            Exception last=first;
            for(int attempt=1;attempt<=FACEBOOK_INFO_NETWORK_RETRIES;attempt++) {
                try {
                    Thread.sleep(RetryClassifier.backoffMs(RetryClassifier.Kind.NETWORK,attempt));
                    VideoInfo recovered=runInfo(context,url,cookieFile,userAgent,false,false);
                    PerformanceTracker.event(context,"facebook_info_network_retry_recovered");
                    return recovered;
                } catch(InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    first.addSuppressed(interrupted);
                    throw first;
                } catch(Exception retryError) {
                    first.addSuppressed(retryError);
                    last=retryError;
                    if (RetryClassifier.classify(String.valueOf(retryError.getMessage())) != RetryClassifier.Kind.NETWORK)
                        throw first;
                }
            }
            String alternate=FacebookSourceRecovery.alternateOnNetworkFailure(url,last);
            if (alternate.isEmpty()) alternate=FacebookSourceRecovery.alternateOnNetworkFailure(url,first);
            if (alternate.isEmpty()) throw first;
            try {
                VideoInfo recovered=runInfo(context,alternate,cookieFile,userAgent,false,false);
                if (!FacebookSourceRecovery.matchesExpected(alternate,infoId(recovered)))
                    throw new IOException("Facebook network fallback source mismatch");
                PerformanceTracker.event(context,"facebook_verified_network_fallback_recovered");
                return recovered;
            } catch(Exception fallbackError) {
                first.addSuppressed(fallbackError);
                throw first;
            }
        }
    }

    public static void download(Context context, DownloadJob job, String processId, ProgressCallback cb) {
        DownloadControl.Run control = DownloadControl.register(job.id, processId);
        DOWNLOAD_EXEC.execute(() -> {
          synchronized (control.lock) {
            String resolved = "";
            boolean tikTok = false;
            String autoCookie = "";
            String browserCookie = "";
            boolean deleteCookie = false;
            long started = System.currentTimeMillis();
            job.startedAt = started;
            String published = "";
            try {
                DownloadControl.check(processId);
                resolved = SocialUrlCanonicalizer.canonicalize(blank(job.originalUrl) ? job.url : job.originalUrl);
                if (LinkCapture.isAppStoreUrl(resolved))
                    throw new IOException("App store URL is not a media video link");
                resolved = TikTokLinkRecovery.resolveCanonical(context, resolved, 12_000L);
                if (TikTokResolver.isTikTok(job.originalUrl) && !TikTokResolver.isTikTok(resolved)) {
                    TikTokDiagnostics.record(context, "offsite_download_redirect_rejected", "store/non-TikTok landing");
                    resolved = LinkCapture.normalize(job.originalUrl);
                }
                if (TikTokResolver.isTikTok(resolved) || TikTokResolver.isTikTok(job.originalUrl)) TikTokDiagnostics.record(context, "resolve_trace", TikTokResolver.lastTrace());
                if (SocialUrlCanonicalizer.isFacebookShare(resolved))
                    PerformanceTracker.event(context,"facebook_share_engine_fallback_download");
                // Preserve the exact user's share URL as the extractor fallback.
                job.resolvedUrl = resolved;
                if (blank(job.platform)) job.platform = LinkCapture.platform(resolved);
                String strategy = PlatformStrategyEngine.ensure(context, job, resolved);
                job.strategyTrace = job.platform + ":" + strategy + ":attempt=" + job.strategyAttempt;
                PerformanceTracker.event(context, "platform_strategy:" + job.platform + ":" + strategy);
                tikTok = TikTokResolver.isTikTok(resolved);
                autoCookie = effectiveCookieFile(context, resolved, job.cookieFile);
                deleteCookie = !blank(autoCookie) && !autoCookie.equals(job.cookieFile);
                if (tikTok) TikTokDiagnostics.request(context, "download_prepare",
                        blank(job.originalUrl) ? job.url : job.originalUrl, resolved, "pending", autoCookie);
                DownloadControl.check(processId);
                if (tikTok && !blank(job.sourceId)) {
                    String resolvedId = TikTokBrowserFallback.extractVideoId(resolved);
                    if (!SourceBinding.matches(job.sourceId,resolvedId))
                        throw new IllegalStateException("Source identity changed before download");
                }
                EngineRuntime.ensureYoutube(context);
                if (!tikTok && !LinkCapture.isLikelyDirectMedia(resolved)) {
                    // A fresh share with no known source identity can go straight to yt-dlp:
                    // verification of final media still happens before publication.
                    if (blank(job.sourceId)) {
                        PerformanceTracker.event(context, "single_pass_unanalyzed_source:" + job.platform);
                    } else if (AnalysisReusePolicy.canReuse(job, resolved)) {
                        PerformanceTracker.event(context, "analysis_reuse_source_check:" + job.platform);
                    } else {
                        cb.progress(0f, 0L, "", 0L, 0L, "verifying_source");
                        VideoInfo fresh = runInfo(context, resolved, autoCookie, job.userAgent, false, false);
                        String freshId = infoId(fresh);
                        if (blank(freshId) || (!blank(job.sourceId) && !SocialSourceIdentity.matchesRefresh(job,resolved,fresh))) {
                            throw new IllegalStateException("Source identity changed before download");
                        }
                        job.sourceId=freshId;
                    }
                }
                if(blank(job.sourceId))job.sourceId=tikTok?TikTokBrowserFallback.extractVideoId(resolved):resolved;
                String uri;
                if (tikTok && job.audio) {
                    uri = downloadTikTokAudio(context,job,resolved,autoCookie,processId,cb,started);
                } else if (tikTok) {
                    if (TikTokResolver.unresolvedShortLink(resolved)) {
                        if (unresolvedTikTokLite(resolved))
                            throw new IOException("TikTok short link could not be resolved to a video page: TikTok Lite OneLink");
                        TikTokDiagnostics.record(context, "short_url_engine_resolution_download", resolved);
                    }
                    // Only vt/vm TikTok short URLs reach extractor unresolved; OneLink never does.

                    // 4.9.3 TikTok one-shot policy: choose ONE download strategy before bytes
                    // are transferred. There is no automatic fallback after a failed transfer.
                    TikTokBrowserFallback.Result warm = null;
                    try {
                        cb.progress(0f, 0L, "", 0L, 0L, "tiktok_resolving");
                        warm = TikTokBrowserFallback.peekFresh(resolved, 30_000L);
                        if (warm == null || !warm.validFor(resolved))
                            warm = TikTokBrowserFallback.resolve(context, resolved, 12_000L);
                    } catch (Exception e) {
                        TikTokDiagnostics.record(context, "download_browser_session_unavailable", e.toString());
                        warm = null;
                    }

                    // Historical one-shot acceptance marker: direct-once source=
                    // 5.3.1: never commit the one media-transfer attempt to a stale
                    // WebView candidate. Probe headers/status first (no media body transfer).
                    boolean directReady = warm != null && warm.validFor(resolved) && TikTokDirectDownloader.preflight(warm);
                    if (!directReady && warm != null) {
                        TikTokDiagnostics.record(context, "direct_preflight_rejected", warm.mediaUrl);
                        try {
                            TikTokBrowserFallback.invalidate(resolved);
                            TikTokBrowserFallback.Result freshWarm = TikTokBrowserFallback.resolve(context, resolved, 9_000L);
                            if (freshWarm != null && freshWarm.validFor(resolved) && TikTokDirectDownloader.preflight(freshWarm)) {
                                warm = freshWarm;
                                directReady = true;
                            }
                        } catch (Exception ignored) {}
                    }

                    if (directReady && !job.audio && canUseTikTokDirect(job)) {
                        TikTokDiagnostics.record(context, "download_strategy", "direct-preflight-once source=" + resolved);
                        TikTokDiagnostics.request(context, "download", blank(job.originalUrl) ? job.url : job.originalUrl,
                                resolved, "direct-preflight-once", autoCookie);
                        cb.progress(0f, 0L, "", 0L, 0L, "tiktok_direct");
                        uri = TikTokDirectDownloader.download(context, job, processId, warm, cb, started);
                    } else {
                        String oneShotCookie = autoCookie;
                        if (warm != null && warm.validFor(resolved)) {
                            browserCookie = CookieBridge.writeCookieFile(context, resolved, warm.cookies);
                            if (!blank(browserCookie)) oneShotCookie = browserCookie;
                            if (blank(job.userAgent) && !blank(warm.userAgent)) job.userAgent = warm.userAgent;
                        }
                        TikTokDiagnostics.record(context, "download_strategy", "yt-dlp-once source=" + resolved);
                        TikTokDiagnostics.request(context, "download", blank(job.originalUrl) ? job.url : job.originalUrl,
                                resolved, "yt-dlp-once", oneShotCookie);
                        cb.progress(0f, 0L, "", 0L, 0L, "tiktok_extractor");
                        uri = performDownload(context, job, resolved, oneShotCookie, processId, cb, true, started, false);
                    }
                } else {
                    try {
                        uri = performDownload(context, job, resolved, autoCookie, processId, cb, false, started, false);
                    } catch (Exception parseFailure) {
                        String alternate=FacebookSourceRecovery.alternateOnParseFailure(resolved,parseFailure);
                        if (alternate.isEmpty()) throw parseFailure;
                        String inferredId=FacebookSourceRecovery.videoIdFromWatch(alternate);
                        if (!blank(job.sourceId) && !SourceBinding.matches(job.sourceId,inferredId))
                            throw parseFailure;
                        try {
                            VideoInfo checked=runInfo(context,alternate,autoCookie,job.userAgent,false,false);
                            if (!FacebookSourceRecovery.matchesExpected(alternate,infoId(checked)))
                                throw new IOException("Facebook fallback source mismatch");
                            // No alternate platform or blind retry. The same
                            // Facebook video ID was verified by the extractor.
                            DownloadControl.check(processId);
                            uri=performDownload(context,job,alternate,autoCookie,processId,cb,false,started,false);
                            PerformanceTracker.event(context,"facebook_same_id_download_recovery");
                        } catch(Exception retryError) {
                            parseFailure.addSuppressed(retryError);
                            throw parseFailure;
                        }
                    }
                }
                if (blank(uri)) throw new IOException("Download completed but no playable media file was produced");
                published = uri;
                DownloadControl.check(processId);
                if (tikTok) TikTokDiagnostics.success(context, "download", resolved,
                        job.strategyTrace == null ? "" : job.strategyTrace);
                cb.done(uri, null);
            } catch (Exception e) {
                if (tikTok || TikTokResolver.isTikTok(blank(job.originalUrl) ? job.url : job.originalUrl))
                    TikTokDiagnostics.failure(context, "download", blank(job.originalUrl) ? job.url : job.originalUrl,
                            resolved, job.strategyTrace == null ? "" : job.strategyTrace, e);
                if (!blank(published)) { try { context.getContentResolver().delete(android.net.Uri.parse(published), null, null); } catch (Exception ignored) {} }
                cb.done("", e);
            } finally {
                if (deleteCookie) CookieBridge.delete(autoCookie);
                CookieBridge.delete(browserCookie);
                if (control.discard) MediaPublisher.cleanupJobWork(context, job.id);
                DownloadControl.finish(processId);
            }
          }
        });
    }

    private static String downloadTikTokAudio(Context context,DownloadJob job,String resolved,String cookie,
                                               String processId,ProgressCallback cb,long started) throws Exception {
        TikTokAudioRecovery.Candidate candidate=null;
        if (TikTokAudioRecovery.supports(job.audioFormat,job.audioQuality,job.selectedAudioFormatId,
                job.selectedVideoFormatId,job.embedMetadata,job.embedThumbnail,job.subtitleMode)) {
            cb.progress(0f,0L,"",0L,0L,"tiktok_resolving");
            candidate=TikTokAudioRecovery.choose(job.sourceId,resolved,
                    TikTokAudioRecovery.cached(job.sourceId,resolved,System.currentTimeMillis()),
                    audioCandidate(TikTokBrowserFallback.peekAudio(resolved)),new TikTokAudioRecovery.Access() {
                @Override public void check() throws Exception { DownloadControl.check(processId); }
                @Override public boolean preflight(TikTokAudioRecovery.Candidate c) {
                    return TikTokDirectDownloader.preflightAudio(audioResult(c));
                }
                @Override public TikTokAudioRecovery.Candidate refetch() throws Exception {
                    try { return audioCandidate(TikTokBrowserFallback.resolveAudio(context,resolved,9_000L)); }
                    catch(Exception unavailable) {
                        DownloadControl.check(processId);
                        TikTokDiagnostics.record(context,"audio_browser_refetch_unavailable",unavailable.getClass().getSimpleName());
                        return null;
                    }
                }
            });
        }
        DownloadControl.check(processId);
        if (candidate==null) {
            // Preserve explicit format IDs, other codecs, metadata, subtitles and artwork.
            // One canonical extractor attempt remains available when no verified CDN can be reached.
            TikTokDiagnostics.record(context,"download_strategy","audio-source-bound-extractor-once source="+resolved);
            try { return performDownload(context,job,resolved,cookie,processId,cb,true,started,false); }
            catch(Exception failure) { throw actionableTikTokAudioFailure(failure); }
        }
        TikTokDiagnostics.record(context,"download_strategy","audio-java-local-once source="+resolved);
        final TikTokBrowserFallback.Result media=audioResult(candidate);
        // No fallback after bytes, extraction or publication starts. Service owns bounded network retries.
        File source;
        try { source=TikTokDirectDownloader.downloadAudioSource(context,job,processId,media,cb,started); }
        catch(Exception failure) { throw actionableTikTokAudioFailure(failure); }
        DownloadControl.check(processId);
        String fmt=blank(job.audioFormat)?"m4a":job.audioFormat.toLowerCase(Locale.ROOT);
        String name=FilenameRules.batchPrefix(job)+FilenameRules.safeBase(job.title)+" ["+job.sourceId+"]."+fmt;
        File out=new File(source.getParentFile(),name);
        cb.progress(99f,0L,"",source.length(),source.length(),"extracting_audio");
        File audio=AudioConverterEngine.extractDownloadAudio(context,source,out,fmt,job.audioQuality,
                line -> {}, () -> {
                    try { DownloadControl.check(processId); return false; }
                    catch(IOException cancelled) { return true; }
                });
        DownloadControl.check(processId);
        cb.progress(99f,0L,"",audio.length(),audio.length(),"publishing");
        return MediaPublisher.publishFinal(context,audio.getParentFile(),audio,true);
    }

    private static TikTokAudioRecovery.Candidate audioCandidate(TikTokBrowserFallback.Result result) {
        return result==null || blank(result.verifiedAudioSourceId) ? null : new TikTokAudioRecovery.Candidate(
                result.verifiedAudioSourceId,result.pageUrl,result.mediaUrl,result.cookies,result.userAgent);
    }

    private static TikTokBrowserFallback.Result audioResult(TikTokAudioRecovery.Candidate c) {
        return new TikTokBrowserFallback.Result(c.mediaUrl,c.pageUrl,c.cookies,c.userAgent,"",
                "tiktok:"+c.sourceId,c.sourceId);
    }

    private static Exception actionableTikTokAudioFailure(Exception failure) {
        if (failure instanceof javax.net.ssl.SSLException
                || RetryClassifier.classify(failure.getMessage())==RetryClassifier.Kind.NETWORK) {
            return new IOException("TikTok audio network unavailable. Open this same video in the app browser, " +
                    "confirm access, then retry or use another trusted network. TLS verification remains enabled. " +
                    String.valueOf(failure.getMessage()),failure);
        }
        return failure;
    }

    private static String downloadResolvedTikTok(Context context, DownloadJob job, String processId,
                                                 ProgressCallback cb, long started,
                                                 TikTokBrowserFallback.Result br) throws Exception {
        DownloadControl.check(processId);
        if (br == null || !br.validFor(job.resolvedUrl)) throw new IOException("TikTok resolved media unavailable or source mismatch");
        if (!blank(job.sourceId) && !br.sourceKey.equals("tiktok:" + job.sourceId))
            throw new IOException("TikTok resolved media does not match the analyzed source");
        if (canUseTikTokDirect(job)) {
            try {
                return TikTokDirectDownloader.download(context, job, processId, br, cb, started);
            } catch (Exception directError) {
                DownloadControl.check(processId);
                // TikTok CDN URLs are short lived. Refresh the official player once and
                // retry the direct stream before falling back to yt-dlp's generic URL path.
                TikTokBrowserFallback.invalidate(job.url);
                TikTokBrowserFallback.Result fresh = null;
                try { fresh = TikTokBrowserFallback.resolve(context, job.url, 9_000L); } catch (Exception ignored) {}
                if (fresh != null && fresh.validFor(job.resolvedUrl) && !fresh.mediaUrl.equals(br.mediaUrl)) {
                    try { return TikTokDirectDownloader.download(context, job, processId, fresh, cb, started); }
                    catch (Exception ignored) { br = fresh; }
                }
                String cookie = CookieBridge.writeCookieFile(context, job.url, br.cookies);
                try {
                    // Prefer the canonical page with the browser cookies so yt-dlp can
                    // still honor the selected source-supported quality. A raw CDN URL
                    // is used only when quality is automatic/unknown.
                    try {
                        return performDownload(context, job, job.resolvedUrl, cookie, processId, cb, true, started, false);
                    } catch (Exception pageError) {
                        if (!canUseRawTikTokMediaFallback(job)) throw pageError;
                        try {
                            return performDownload(context, job, br.mediaUrl, cookie, processId, cb, false, started, true);
                        } catch (Exception mediaError) {
                            mediaError.addSuppressed(pageError);
                            throw mediaError;
                        }
                    }
                } finally {
                    CookieBridge.delete(cookie);
                }
            }
        }
        String cookie = CookieBridge.writeCookieFile(context, job.url, br.cookies);
        try {
            try {
                // 4.8.4: canonical extractor + browser cookies is the primary
                // TikTok route because it can obtain/merge a separate audio stream.
                return performDownload(context, job, job.resolvedUrl, cookie, processId, cb, true, started, false);
            } catch (Exception pageError) {
                if (MediaIntegrityPolicy.requiresVideoAudio(job)) {
                    // Last-resort direct CDN is accepted ONLY if MediaVerifier proves
                    // that the progressive MP4 already contains an audio track.
                    try {
                        return TikTokDirectDownloader.download(context, job, processId, br, cb, started);
                    } catch (Exception directError) {
                        directError.addSuppressed(pageError);
                        throw directError;
                    }
                }
                if (!canUseRawTikTokMediaFallback(job)) throw pageError;
                try {
                    return performDownload(context, job, br.mediaUrl, cookie, processId, cb, false, started, true);
                } catch (Exception mediaError) {
                    mediaError.addSuppressed(pageError);
                    throw mediaError;
                }
            }
        } finally {
            CookieBridge.delete(cookie);
        }
    }

    private static String performDownload(Context context, DownloadJob job, String url, String cookieFile,
                                          String processId, ProgressCallback cb, boolean tikTokFallback,
                                          long started, boolean forceTikTokHeaders) throws Exception {
        DownloadControl.check(processId);
        YoutubeDLRequest r = new YoutubeDLRequest(url);
        File tempDir = WritableTempGuard.configureYoutube(context);
        File outDir = MediaPublisher.jobWorkDir(context, job.id);
        // The final Instagram compatibility attempt must not resume bytes from
        // the earlier source. Keep both attempts inside this job's private work.
        if (instagramAlternateSource(job)) outDir = new File(outDir, "instagram_alternate");
        if (!outDir.exists() && !outDir.mkdirs()) throw new IOException("Unable to create download work directory");
        String out = new File(outDir, FilenameRules.outputTemplate(job)).getAbsolutePath();

        r.addOption("--no-playlist");
        // Use the job's isolated writable work dir for yt-dlp fragments and final media.
        r.addOption("-P", "temp:" + outDir.getAbsolutePath());
        r.addOption("--cache-dir", new File(tempDir,"yt-cache").getAbsolutePath());
        r.addOption("--newline");
        r.addOption("--continue");
        r.addOption("--part");
        r.addOption("--no-mtime");
        r.addOption("--abort-on-unavailable-fragments");
        File finalPath = new File(outDir, "final-path.txt");
        if (finalPath.exists() && !finalPath.delete()) throw new IOException("Cannot clear prior final path");
        r.addCommands(java.util.Arrays.asList("--print-to-file", "after_move:filepath", finalPath.getAbsolutePath()));
        r.addOption("--retries", tikTokFallback ? "0" : "10");
        r.addOption("--fragment-retries", tikTokFallback ? "0" : "10");
        r.addOption("--extractor-retries", tikTokFallback ? "0" : "4");
        if (!tikTokFallback) {
            // Fixed one-second loops repeatedly hit the same throttled CDN edge in the
            // Stage 16 device trace. Use yt-dlp's bounded exponential syntax while
            // retaining its existing retry counts and resumable .part files.
            r.addOption("--retry-sleep", "http:exp=1:8");
            r.addOption("--retry-sleep", "fragment:exp=1:8");
            r.addOption("--retry-sleep", "extractor:exp=1:4");
        }
        r.addOption("--file-access-retries", tikTokFallback ? "0" : "4");
        // 5.3.1: titles from social sites may contain "/" ":" emoji/control chars.
        // Force a filesystem-safe filename before yt-dlp opens any output file.
        r.addOption("--windows-filenames");
        r.addOption("--trim-filenames", "180");
        r.addOption("-o", out);
        applyAccessOptions(context, r, url, cookieFile, job.userAgent, tikTokFallback, forceTikTokHeaders);
        PlatformStrategyEngine.apply(context, r, job, url);

        boolean tiktokDirect = forceTikTokHeaders || TikTokBrowserFallback.looksLikeTikTokMedia(url);
        boolean social = LinkCapture.isSocial(url) && !tiktokDirect;
        boolean direct = LinkCapture.isLikelyDirectMedia(url) || tiktokDirect;
        boolean streaming = LinkCapture.isStreamingManifest(url);
        // Keep native startup lazy. Direct progressive files need no FFmpeg unless
        // conversion/metadata/subtitle work is requested.
        boolean needsFfmpeg = job.audio || !direct || streaming || job.embedMetadata || job.embedThumbnail ||
                (job.subtitleMode != null && !"none".equals(job.subtitleMode));
        if (needsFfmpeg) EngineRuntime.ensureFfmpeg(context);

        int parallel = smartParallelism(context, social, direct && !streaming, job.turbo);
        if (tiktokDirect) parallel = Math.min(parallel, 4);
        boolean ariaEnabled = false;
        if (job.turbo && direct && !social && !streaming) {
            ariaEnabled = EngineRuntime.ensureAria(context);
            if (ariaEnabled) {
                r.addOption("--downloader", "libaria2c.so");
                r.addOption("--downloader-args", "aria2c:-x " + parallel + " -s " + parallel +
                        " -k 1M --min-split-size=1M --file-allocation=none --continue=true --summary-interval=1" +
                        " --max-tries=5 --retry-wait=1 --connect-timeout=8 --timeout=20 --disk-cache=32M");
            }
        }
        if (!ariaEnabled) r.addOption("--concurrent-fragments", String.valueOf(parallel));
        r.addOption("--buffer-size", direct && !streaming ? "8M" : ((social || streaming) ? "4M" : "8M"));

        if (MediaIntegrityPolicy.shouldCheckFormats(job, url)) {
            r.addOption("--check-formats");
        }
        applyFormatOptions(r, job);
        if (job.embedMetadata) r.addOption("--embed-metadata");
        if (job.embedThumbnail) {
            r.addOption("--write-thumbnail");
            r.addOption("--embed-thumbnail");
        }
        applySubtitleOptions(r, job);

        Function3<Float, Long, String, Unit> progressCb = new Function3<Float, Long, String, Unit>() {
            @Override public Unit invoke(Float p, Long eta, String line) {
                float pct = p == null || p < 0 ? 0f : p;
                long e = eta == null || eta < 0 ? 0L : eta;
                long total = parseTotalBytes(line);
                long downloaded = total > 0 ? Math.max(0L, Math.min(total, Math.round(total * (pct / 100d)))) : 0L;
                cb.progress(pct, e, parseSpeed(line), downloaded, total, parsePhase(line));
                return Unit.INSTANCE;
            }
        };

        EngineUpdateManager.ENGINE_READ.lock();
        try {
            DownloadControl.check(processId);
            YoutubeDL.getInstance().execute(r, processId, progressCb);
        } finally {
            EngineUpdateManager.ENGINE_READ.unlock();
        }
        DownloadControl.check(processId);
        File completed = resolveFinalMediaFile(outDir, finalPath, job.audio, started);
        if (completed == null) throw new IOException("Download finished but final media could not be identified");
        cb.progress(99f, 0L, "", completed.length(), completed.length(), "verifying");
        MediaVerifier.Result verified = MediaVerifier.verify(completed, job.audio);
        if (!verified.valid) throw new IOException("Media verification failed: " + verified.reason);
        if (MediaIntegrityPolicy.requiresVideoAudio(job) && !verified.hasAudio) {
            throw new IOException("Social video is missing its audio track");
        }
        DownloadControl.check(processId);
        cb.progress(99f, 0L, "", completed.length(), completed.length(), "publishing");
        return MediaPublisher.publishFinal(context, outDir, completed, job.audio);
    }

    private static File resolveFinalMediaFile(File outDir, File marker, boolean audioOnly, long started) throws IOException {
        File canonicalDir = outDir.getCanonicalFile();
        List<File> marked = new ArrayList<>();
        if (marker != null && marker.isFile()) {
            try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(marker))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    File candidate = new File(line).getCanonicalFile();
                    if (!canonicalDir.equals(candidate.getParentFile())) continue;
                    if (!candidate.isFile() || !MediaVerifier.isFinalMediaName(candidate.getName())) continue;
                    MediaVerifier.Result check = MediaVerifier.verify(candidate, audioOnly);
                    if (check.valid) marked.add(candidate);
                }
            } catch (IOException ignored) {
                // Fall through to the job-isolated directory scan below.
            }
        }
        if (!marked.isEmpty()) return newest(marked);

        // Android yt-dlp builds can occasionally fail to emit --print-to-file even
        // after the postprocessor moved the final file. Because every job has a
        // private directory, scanning only that directory is deterministic and
        // cannot select another job's/stale preview file.
        List<File> verified = new ArrayList<>();
        File[] files = canonicalDir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file == null || !file.isFile()) continue;
                if (file.getName().equals(marker == null ? "" : marker.getName())) continue;
                if (file.lastModified() + 10_000L < started) continue;
                if (!MediaVerifier.isFinalMediaName(file.getName())) continue;
                MediaVerifier.Result check = MediaVerifier.verify(file, audioOnly);
                if (check.valid) verified.add(file.getCanonicalFile());
            }
        }
        return verified.isEmpty() ? null : newest(verified);
    }

    private static boolean instagramAlternateSource(DownloadJob job) {
        return job != null && !job.audio && job.compatibilityRetryCount >= 3 &&
                job.originalUrl != null &&
                job.originalUrl.toLowerCase(Locale.ROOT).contains("instagram.com/");
    }

    private static File newest(List<File> files) {
        File best = null;
        for (File f : files) {
            if (best == null || f.lastModified() > best.lastModified() ||
                    (f.lastModified() == best.lastModified() && f.length() > best.length())) best = f;
        }
        return best;
    }

    private static boolean canUseTikTokDirect(DownloadJob job) {
        // The direct downloader verifies the final video AND enforces the social
        // video+audio contract itself, so requiresVideoAudio() is not a blocker.
        if (job == null || job.audio || !blank(job.selectedVideoFormatId) || job.embedMetadata || job.embedThumbnail || !"mp4".equalsIgnoreCase(job.videoContainer)) return false;
        String q = blank(job.quality) ? "medium" : job.quality.trim();
        if ("medium".equalsIgnoreCase(q) || "best".equalsIgnoreCase(q)) return blank(job.formats);
        if (!q.matches("[1-9][0-9]*")) return false;
        if (blank(job.formats)) return false;
        String[] values = job.formats.split(",");
        int count = 0;
        boolean selected = false;
        for (String value : values) {
            String v = value.trim();
            if (v.isEmpty()) continue;
            count++;
            if (q.equals(v)) selected = true;
        }
        return count == 1 && selected;
    }

    private static boolean canUseRawTikTokMediaFallback(DownloadJob job) {
        if (job == null || job.audio) return true;
        if (MediaIntegrityPolicy.requiresVideoAudio(job)) return false;
        String q = blank(job.quality) ? "medium" : job.quality.trim();
        return "medium".equalsIgnoreCase(q) || "best".equalsIgnoreCase(q) || blank(job.formats);
    }

    public static void stop(String processId) { stop(processId, false); }

    public static void stop(String processId, boolean discard) {
        if (blank(processId)) return;
        DownloadControl.stop(processId, discard);
        TikTokDirectDownloader.cancel(processId);
        try { YoutubeDL.getInstance().destroyProcessById(processId); } catch (Exception ignored) {}
    }

    private static void applyFormatOptions(YoutubeDLRequest r, DownloadJob job) {
        if (job.audio) {
            String fmt = blank(job.audioFormat) ? "m4a" : job.audioFormat;
            // 4.8.4 audio contract: prefer a real audio-only stream, but if the
            // platform only exposes a progressive A/V format, allow yt-dlp+FFmpeg
            // to extract its audio instead of leaving the job stuck at 0%.
            // Audio-only extraction: reject silent video-only formats and fall back to
            // a valid audio stream or a combined A/V source for FFmpeg extraction.
            // Video download job choices/engine remain untouched.
            String audioFallback = "ba[ext=m4a]/ba/b[acodec!=none]/bv*+ba";
            // Telegram's public embed extractor may mark MP4 audio/video codecs as
            // "unknown" even when the progressive file contains AAC (verified by
            // ffprobe on a real post). The normal selectors then find no format.
            // Fallback to best combined stream ONLY for a recognized public Telegram
            // post; FFmpeg extracts audio and MediaVerifier still rejects silent files.
            if (TelegramPostLinks.isPublicPost(job.originalUrl))
                audioFallback += "/b";
            if (!blank(job.selectedAudioFormatId)) {
                String preferred = job.selectedAudioFormatId.trim().replaceAll("[^A-Za-z0-9._-]", "");
                r.addOption("-f", (preferred.isEmpty() ? "" : preferred+"[acodec!=none]/")+audioFallback);
            } else r.addOption("-f", audioFallback);
            r.addOption("-x");
            r.addOption("--audio-format", fmt);
            String aq = blank(job.audioQuality) || "best".equalsIgnoreCase(job.audioQuality) ? "0" : job.audioQuality + "K";
            r.addOption("--audio-quality", aq);
            return;
        }

        String q = blank(job.quality) ? "medium" : job.quality;
        String container = blank(job.videoContainer) ? "mp4" : job.videoContainer.toLowerCase(Locale.ROOT);
        String format;
        boolean medium = "medium".equalsIgnoreCase(q);
        boolean requireAv = MediaIntegrityPolicy.requiresVideoAudio(job);

        // Exact source-format selection. For the three core social platforms a
        // video-only choice MUST be paired with audio. Never silently fall back
        // to the video-only ID and publish a mute file.
        if (!blank(job.selectedVideoFormatId)) {
            String exact = job.selectedVideoFormatId.trim();
            if (job.selectedVideoFormatHasAudio) format = exact;
            else if (requireAv) format = exact + "+ba[ext=m4a]/" + exact + "+ba";
            else format = exact + "+ba[ext=m4a]/" + exact + "+ba/" + exact;
            r.addOption("-f", format);
            if ("mp4".equals(container)) {
                r.addOption("--merge-output-format", "mp4");
                r.addOption("--remux-video", "mp4");
            } else {
                r.addOption("--merge-output-format", "mkv");
                r.addOption("--remux-video", "mkv");
            }
            r.addOption("--no-keep-video");
            return;
        }

        // Medium = best real source format at/below 720p, then nearest source
        // fallback. The final file still has to satisfy the A/V contract.
        if (medium) r.addOption("--format-sort", "res:720");

        if ("mp4".equals(container)) {
            if (requireAv && TikTokResolver.isTikTok(job.originalUrl)) {
                r.addOption("--format-sort", (medium ? "res:720" : "res") + ",+codec:avc:m4a");
            }
            if (medium && instagramAlternateSource(job)) {
                // The earlier progressive source completed with invalid media.
                // Ask yt-dlp for separate source streams first, retaining a
                // progressive fallback and the mandatory video+audio check.
                format = "bv[ext=mp4][height<=720]+ba[ext=m4a]/" +
                        "bv[height<=720]+ba/bv[ext=mp4]+ba[ext=m4a]/bv+ba/" +
                        "b[ext=mp4][height<=720]/b[height<=720]/b";
            } else if (medium) {
                if (requireAv) {
                    // Prefer one progressive A/V file first. If the site exposes
                    // DASH/separate streams, explicitly fetch video+best audio and
                    // let FFmpeg merge them. No video-only terminal fallback.
                    format = "b[ext=mp4][height<=720]/b[height<=720]/" +
                            "bv[ext=mp4][height<=720]+ba[ext=m4a]/bv[height<=720]+ba/" +
                            "b[ext=mp4]/b/bv[ext=mp4]+ba[ext=m4a]/bv+ba";
                } else {
                    format = "bv*[ext=mp4][height<=720]+ba[ext=m4a]/b[ext=mp4][height<=720]/" +
                            "bv*[height<=720]+ba/b[height<=720]/bv*[ext=mp4]+ba[ext=m4a]/b[ext=mp4]/bv*+ba/b";
                }
            } else if ("best".equalsIgnoreCase(q)) {
                if (requireAv) format = "b[ext=mp4]/b/bv[ext=mp4]+ba[ext=m4a]/bv+ba";
                else format = "bv*[ext=mp4]+ba[ext=m4a]/b[ext=mp4]/bv*+ba/b";
            } else {
                if (!q.matches("[1-9][0-9]{0,4}")) throw new IllegalArgumentException("Invalid quality");
                if (requireAv) {
                    format = "b[ext=mp4][height=" + q + "]/b[height=" + q + "]/" +
                            "bv[ext=mp4][height=" + q + "]+ba[ext=m4a]/bv[height=" + q + "]+ba";
                } else {
                    format = "bv*[ext=mp4][height=" + q + "]+ba[ext=m4a]/b[ext=mp4][height=" + q + "]/" +
                            "bv*[height=" + q + "]+ba/b[height=" + q + "]";
                }
            }
            r.addOption("-f", format);
            r.addOption("--merge-output-format", "mp4");
            r.addOption("--remux-video", "mp4");
        } else {
            if (medium) format = requireAv ? "b[height<=720]/bv[height<=720]+ba/b/bv+ba" : "bv*[height<=720]+ba/b[height<=720]/bv*+ba/b";
            else if ("best".equalsIgnoreCase(q)) format = requireAv ? "b/bv+ba" : "bv*+ba/b";
            else {
                if (!q.matches("[1-9][0-9]{0,4}")) throw new IllegalArgumentException("Invalid quality");
                format = requireAv ? "b[height=" + q + "]/bv[height=" + q + "]+ba" : "bv*[height=" + q + "]+ba/b[height=" + q + "]";
            }
            r.addOption("-f", format);
            r.addOption("--merge-output-format", "mkv");
            r.addOption("--remux-video", "mkv");
        }

        r.addOption("--no-keep-video");
    }

    private static void applySubtitleOptions(YoutubeDLRequest r, DownloadJob j) {
        if (j.audio || blank(j.subtitleMode) || "none".equals(j.subtitleMode)) return;
        r.addOption("--write-subs");
        r.addOption("--write-auto-subs");
        r.addOption("--sub-format", "srt/best");
        if ("ar".equals(j.subtitleMode)) r.addOption("--sub-langs", "ar.*,ar");
        else if ("en".equals(j.subtitleMode)) r.addOption("--sub-langs", "en.*,en");
        else if ("ar_en".equals(j.subtitleMode)) r.addOption("--sub-langs", "ar.*,ar,en.*,en");
        else r.addOption("--sub-langs", "all");
        r.addOption("--embed-subs");
    }

    private static void applyAccessOptions(Context context, YoutubeDLRequest r, String url, String cookieFile, String userAgent, boolean tikTokFallback, boolean forceTikTokHeaders) {
        r.addOption("--socket-timeout", tikTokFallback ? "12" : "25");
        if (!blank(cookieFile) && new File(cookieFile).exists()) {
            if (CookieHealth.isUsable(cookieFile)) r.addOption("--cookies", cookieFile);
            else PerformanceTracker.event(context, "cookie_health_invalid_ignored:" + LinkCapture.platform(url));
        }
        String lower = url == null ? "" : url.toLowerCase(Locale.ROOT);
        String ua = userAgent == null ? "" : userAgent.trim();
        boolean tikTokAccess = forceTikTokHeaders || lower.contains("tiktok.com") || TikTokBrowserFallback.looksLikeTikTokMedia(url);
        boolean facebookAccess = lower.contains("facebook.com") || lower.contains("fb.watch");
        boolean instagramAccess = lower.contains("instagram.com");
        // LIVE 2026-10-08 regression reproducer: both public TikTok and
        // Instagram work without synthetic UA, fail with Chrome/140 spoofing.
        // yt-dlp manages its maintained UA on platform PAGE extractors.
        // Explicit browser UA is retained only for direct captured media/CDN
        // where source access genuinely requires the matching WebView identity.
        boolean pageSocial = tikTokAccess || facebookAccess || instagramAccess;
        if (!ua.isEmpty() && (forceTikTokHeaders || !pageSocial))
            r.addOption("--user-agent", ua);
        if (tikTokAccess) {
            r.addOption("--referer", "https://www.tiktok.com/");
            r.addOption("--add-header", "Accept-Language:en-US,en;q=0.9");
            r.addOption("--add-header", "Accept:text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            r.addOption("--add-header", "Sec-Fetch-Site:none");
            r.addOption("--add-header", "Sec-Fetch-Mode:navigate");
            r.addOption("--add-header", "Sec-Fetch-Dest:document");
            r.addOption("--add-header", "Upgrade-Insecure-Requests:1");
            if (tikTokFallback && lower.contains("tiktok.com")) {
                // Current yt-dlp only enables TikTok's mobile API path when app_info
                // (or a genuine device_id) is supplied. Keep a stable local install ID
                // so the fallback is actually attempted instead of immediately reporting
                // "No working app info is available".
                r.addOption("--extractor-args", tikTokExtractorArgs(context));
            }
        } else if (instagramAccess) {
            r.addOption("--referer", "https://www.instagram.com/");
            r.addOption("--add-header", "Accept-Language:en-US,en;q=0.9");
        } else if (facebookAccess) {
            r.addOption("--referer", "https://www.facebook.com/");
            r.addOption("--add-header", "Accept-Language:en-US,en;q=0.9");
            r.addOption("--add-header", "Sec-Fetch-Site:same-origin");
        } else if (lower.contains("x.com") || lower.contains("twitter.com")) r.addOption("--referer", "https://x.com/");
    }

    private static String tikTokExtractorArgs(Context context) {
        // Do not pin an old TikTok API hostname. Let the bundled/current yt-dlp choose
        // its maintained endpoint while we only provide a stable per-install IID.
        return "tiktok:app_info=" +
                tikTokInstallId(context) + "/musical_ly/35.1.3/2023501030/0";
    }

    private static String tikTokInstallId(Context context) {
        final String prefsName = "download_anything_tiktok_runtime";
        final String key = "install_iid";
        try {
            SharedPreferences p = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
            String existing = p.getString(key, "");
            if (existing != null && existing.matches("\\d{19}")) return existing;
            long span = 7325099899999994577L - 7250000000000000000L;
            long random = new SecureRandom().nextLong() & Long.MAX_VALUE;
            long iid = 7250000000000000000L + (random % span);
            String value = Long.toString(iid);
            p.edit().putString(key, value).apply();
            return value;
        } catch (Exception ignored) {
            return "7250000000000000001";
        }
    }

    private static String effectiveCookieFile(Context context, String url, String explicit) {
        try {
            CookieManager cm = CookieManager.getInstance();
            String lower = url == null ? "" : url.toLowerCase(Locale.ROOT);
            String origin = "";
            if (TikTokResolver.isTikTok(url)) origin = "https://www.tiktok.com/";
            else if (lower.contains("facebook.com") || lower.contains("fb.watch")) origin = "https://www.facebook.com/";
            else if (lower.contains("instagram.com")) origin = "https://www.instagram.com/";

            if (!origin.isEmpty()) {
                // 5.3.1: prefer the CURRENT in-app browser session over an old
                // cookie snapshot stored when the job was first queued. This lets
                // the user log in in Browser and then Retry without recreating the job.
                String baseRaw = cm.getCookie(origin);
                String pageRaw = LinkCapture.isHttp(url) ? cm.getCookie(url) : "";
                String raw = mergeRawCookies(baseRaw, pageRaw);
                String path = CookieBridge.writeCookieFile(context, origin, raw);
                if (!blank(path)) return path;
            }
        } catch (Exception ignored) {}
        return !blank(explicit) && new File(explicit).exists() ? explicit : "";
    }

    private static String mergeRawCookies(String a, String b) {
        java.util.LinkedHashMap<String,String> map = new java.util.LinkedHashMap<>();
        for (String raw : new String[]{a,b}) {
            if (blank(raw)) continue;
            for (String part : raw.split(";")) {
                int eq = part.indexOf('=');
                if (eq <= 0) continue;
                String k = part.substring(0,eq).trim();
                String v = part.substring(eq+1).trim();
                if (!k.isEmpty()) map.put(k,v);
            }
        }
        StringBuilder out = new StringBuilder();
        for (java.util.Map.Entry<String,String> e : map.entrySet()) {
            if (out.length() > 0) out.append("; ");
            out.append(e.getKey()).append('=').append(e.getValue());
        }
        return out.toString();
    }

    private static boolean unresolvedTikTokLite(String url) {
        if (!TikTokResolver.unresolvedShortLink(url)) return false;
        try {
            return TikTokDeepLinkParser.looksLikeTikTokOneLinkHost(
                    android.net.Uri.parse(url).getHost());
        } catch (Exception ignored) { return false; }
    }

    private static boolean shouldTikTokFallback(String url, Exception e) {
        if (!TikTokResolver.isTikTok(url) || e == null) return false;
        StringBuilder details = new StringBuilder();
        Throwable cause = e;
        for (int depth = 0; cause != null && depth < 6; depth++, cause = cause.getCause())
            details.append(' ').append(cause.getMessage());
        String s = details.toString().toLowerCase(Locale.ROOT);
        if (s.contains("cancelled") || s.contains("canceled") || s.contains("interrupted") ||
                s.contains("no space left") || s.contains("permission denied") || s.contains("source identity changed")) return false;
        return s.contains("impersonation") || s.contains("status code 0") || s.contains("unable to extract") ||
                s.contains("universal data") || s.contains("video unavailable") || s.contains("403") ||
                s.contains("forbidden") || s.contains("webpage video data") || s.contains("challenge") ||
                s.contains("no working app info") || s.contains("no video formats") || s.contains("login required") ||
                s.contains("sign in") || s.contains("not a bot") || s.contains("confirm you") ||
                s.contains("timed out") || s.contains("connection reset") ||
                s.contains("requested format is not available") || s.contains("unexpected response from webpage") ||
                s.contains("http error 429") || s.contains("http error 502") || s.contains("http error 503");
    }

    private static long parseTotalBytes(String line) {
        if (line == null) return 0L;
        Matcher m = TOTAL.matcher(line);
        if (!m.find()) return 0L;
        try {
            double v = Double.parseDouble(m.group(1));
            String unit = m.group(2).toUpperCase(Locale.ROOT);
            double mul = 1d;
            if (unit.equals("KIB")) mul = 1024d;
            else if (unit.equals("MIB")) mul = 1024d * 1024d;
            else if (unit.equals("GIB")) mul = 1024d * 1024d * 1024d;
            else if (unit.equals("KB")) mul = 1000d;
            else if (unit.equals("MB")) mul = 1000d * 1000d;
            else if (unit.equals("GB")) mul = 1000d * 1000d * 1000d;
            return Math.max(0L, Math.round(v * mul));
        } catch (Exception e) { return 0L; }
    }

    private static String parsePhase(String line) {
        String s = line == null ? "" : line.toLowerCase(Locale.ROOT);
        if (s.contains("merger") || s.contains("merging formats")) return "merging";
        if (s.contains("postprocess") || s.contains("embed") || s.contains("metadata")) return "postprocessing";
        if (s.contains("download")) return "downloading";
        return "preparing";
    }

    private static String parseSpeed(String line) {
        if (line == null) return "";
        Matcher m = SPEED.matcher(line);
        return m.find() ? m.group(1) : "";
    }

    private static int smartParallelism(Context context, boolean social, boolean direct, boolean turbo) {
        int base = 2;
        if (turbo) {
            base = social ? 4 : 8;
            try {
                ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
                Network n = cm == null ? null : cm.getActiveNetwork();
                NetworkCapabilities c = n == null ? null : cm.getNetworkCapabilities(n);
                if (c != null) {
                    int downKbps = c.getLinkDownstreamBandwidthKbps();
                    boolean wifi = c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
                    boolean cell = c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
                    if (wifi) {
                        if (downKbps >= 200_000) base = social ? 6 : (direct ? 12 : 10);
                        else if (downKbps >= 100_000) base = social ? 5 : (direct ? 10 : 9);
                        else if (downKbps >= 40_000) base = social ? 4 : (direct ? 8 : 7);
                        else if (downKbps >= 15_000) base = social ? 3 : 6;
                        else base = social ? 2 : 4;
                    } else if (cell) {
                        if (downKbps >= 80_000) base = social ? 4 : (direct ? 8 : 7);
                        else if (downKbps >= 30_000) base = social ? 3 : 5;
                        else base = social ? 2 : 3;
                    }
                }
            } catch (Exception ignored) {}
        }

        // 3.1.4: keep total network pressure bounded when 2-3 jobs run together.
        // Do not multiply 12-16 fragments by every active job; that lowers throughput
        // on phones due to socket contention, memory pressure and thermal throttling.
        int activeJobs = Math.max(1, DownloadQueueStore.activeCount(context));
        if (activeJobs > 1) base = Math.max(2, (base + activeJobs - 1) / activeJobs);
        return Math.max(2, Math.min(12, base));
    }

    private static String infoId(VideoInfo info) {
        if (info == null) return "";
        try {
            Object value = info.getClass().getMethod("getId").invoke(info);
            return value == null ? "" : String.valueOf(value);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static boolean blank(String s) { return s == null || s.trim().isEmpty(); }
}
