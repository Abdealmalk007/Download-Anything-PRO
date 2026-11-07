package com.downloadanything.pro;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Browser-backed TikTok resolver.
 *
 * The stable youtubedl-android runtime can lack curl_cffi/browser impersonation.
 * This resolver uses Android System WebView as the actual browser session, then
 * switches to TikTok's official embed player when a post ID is known. The player
 * forces the real video request to occur, which is intercepted and handed to the
 * direct downloader with the same cookies/user-agent.
 */
public final class TikTokBrowserFallback {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService IO = RuntimeExecutors.io();
    private static final long CACHE_TTL_MS = 180_000L;
    private static final long SESSION_HARD_TIMEOUT_MS = 18_000L;
    private static final String UA = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36";
    private static final Pattern VIDEO_ID = Pattern.compile("(?:/video/|/player/v1/)(\\d{10,24})");

    private static final Map<String, CacheEntry> CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Session> INFLIGHT = new ConcurrentHashMap<>();
    private static volatile WeakReference<Activity> LAST_ACTIVITY = new WeakReference<>(null);

    public static final class Result {
        public final String mediaUrl;
        public final String pageUrl;
        public final String cookies;
        public final String userAgent;
        public final String title;
        public final String sourceKey;
        public final String verifiedAudioSourceId;

        Result(String mediaUrl, String pageUrl, String cookies, String userAgent, String title, String sourceKey) {
            this(mediaUrl,pageUrl,cookies,userAgent,title,sourceKey,"");
        }
        Result(String mediaUrl, String pageUrl, String cookies, String userAgent, String title,
               String sourceKey, String verifiedAudioSourceId) {
            this.mediaUrl = mediaUrl == null ? "" : mediaUrl;
            this.pageUrl = pageUrl == null ? "" : pageUrl;
            this.cookies = cookies == null ? "" : cookies;
            this.userAgent = userAgent == null ? UA : userAgent;
            this.title = title == null ? "" : title;
            this.sourceKey = sourceKey == null ? "" : sourceKey;
            this.verifiedAudioSourceId = verifiedAudioSourceId == null ? "" : verifiedAudioSourceId;
        }

        public boolean valid() { return isHttp(mediaUrl) && looksLikeTikTokMedia(mediaUrl); }
        public boolean validFor(String sourceUrl) { return valid() && !sourceKey.isEmpty() && sourceKey.equals(key(sourceUrl)); }
    }

    private static final class CacheEntry {
        final Result result;
        final long savedAt;
        CacheEntry(Result result) { this.result = result; this.savedAt = System.currentTimeMillis(); }
    }

    private static final class Session {
        final String key;
        final String canonical;
        final boolean audioBound;
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean done = new AtomicBoolean(false);
        final AtomicBoolean playerTried = new AtomicBoolean(false);
        final AtomicReference<WebView> view = new AtomicReference<>();
        final WeakReference<Activity> activity;
        volatile ViewGroup attachedRoot;
        volatile Result result;
        volatile Throwable error;
        volatile String lastPage = "";
        Session(String key, String canonical, Activity activity, boolean audioBound) {
            this.key = key;
            this.canonical = canonical;
            this.activity = new WeakReference<>(activity);
            this.audioBound = audioBound;
        }
    }

    private TikTokBrowserFallback() {}

    /** Start resolving early while the user is still looking at the preview. */
    public static void prefetch(Context context, String url) {
        if (context == null || !TikTokResolver.isTikTok(url)) return;
        final Context owner = context;
        if (context instanceof Activity) LAST_ACTIVITY = new WeakReference<>((Activity) context);
        final String key = key(url);
        if (cached(key) != null || INFLIGHT.containsKey(key)) return;
        IO.execute(() -> {
            try { resolve(owner, url, 12_000L); } catch (Exception ignored) {}
        });
    }

    public static Result peek(String url) {
        return cached(key(url));
    }

    /** Return cached media only when it is both source-bound and very fresh. */
    public static Result peekFresh(String url, long maxAgeMs) {
        String k = key(url);
        CacheEntry e = CACHE.get(k);
        if (e == null) return null;
        long max = Math.max(1_000L, Math.min(maxAgeMs, CACHE_TTL_MS));
        if (System.currentTimeMillis() - e.savedAt > max || e.result == null || !e.result.validFor(url)) {
            CACHE.remove(k, e);
            return null;
        }
        return e.result;
    }

    public static void invalidate(String url) {
        CACHE.remove(key(url));
    }

    public static Result resolve(Context context, String url, long waitMs) throws Exception {
        return resolve(context,url,waitMs,false);
    }

    /** Audio recovery requires the requested item's own video metadata, never page recommendations. */
    public static Result resolveAudio(Context context, String url, long waitMs) throws Exception {
        CACHE.remove("audio:" + key(url));
        return resolve(context,url,waitMs,true);
    }

    public static Result peekAudio(String url) {
        return cached("audio:" + key(url));
    }

    private static Result resolve(Context context, String url, long waitMs, boolean audioBound) throws Exception {
        if (context == null || !TikTokResolver.isTikTok(url)) return null;
        final String canonical = TikTokResolver.resolve(url);
        final String key = (audioBound ? "audio:" : "") + key(canonical);

        Result hit = cached(key);
        if (hit != null) return hit;

        Activity a = context instanceof Activity ? (Activity) context : LAST_ACTIVITY.get();
        if (a != null && a.isFinishing()) a = null;
        Session mine = new Session(key, canonical, a, audioBound);
        Session session = INFLIGHT.putIfAbsent(key, mine);
        if (session == null) {
            session = mine;
            start(context, canonical, session);
        }

        long timeout = Math.max(1_500L, Math.min(waitMs <= 0 ? 11_000L : waitMs, SESSION_HARD_TIMEOUT_MS + 1000L));
        if (!session.latch.await(timeout, TimeUnit.MILLISECONDS)) {
            Result late = cached(key);
            if (late != null) return late;
            throw new IllegalStateException("TikTok browser fallback timed out");
        }
        if (session.result != null && session.result.valid()) return session.result;
        if (session.error != null) throw new IllegalStateException("TikTok browser fallback failed", session.error);
        throw new IllegalStateException("TikTok browser fallback could not locate playable media");
    }

    private static void start(Context context, String canonical, Session session) {
        MAIN.post(() -> {
            if (session.done.get()) return;
            final WebView web;
            try {
                Activity activity = session.activity.get();
                Context webContext = activity != null ? activity : context.getApplicationContext();
                web = new WebView(webContext);
                session.view.set(web);

                WebSettings s = web.getSettings();
                s.setJavaScriptEnabled(true);
                s.setDomStorageEnabled(true);
                s.setDatabaseEnabled(true);
                s.setAllowFileAccess(false);
                s.setAllowContentAccess(false);
                s.setJavaScriptCanOpenWindowsAutomatically(false);
                s.setSupportMultipleWindows(false);
                // The hidden official player is muted, but it must be allowed to start so WebView
                // actually requests TikTok's playable CDN stream.
                s.setMediaPlaybackRequiresUserGesture(false);
                s.setLoadsImagesAutomatically(true);
                s.setBlockNetworkImage(false);
                s.setCacheMode(WebSettings.LOAD_DEFAULT);
                s.setUserAgentString(UA);
                if (Build.VERSION.SDK_INT >= 21) s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

                CookieManager cm = CookieManager.getInstance();
                cm.setAcceptCookie(true);
                if (Build.VERSION.SDK_INT >= 21) cm.setAcceptThirdPartyCookies(web, true);

                // A WebView attached to a live Activity is much more reliable for player/media loading
                // than a detached application-context WebView. Keep it nearly invisible but laid out.
                if (activity != null && !activity.isFinishing()) {
                    ViewGroup root = activity.findViewById(android.R.id.content);
                    if (root != null) {
                        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(160, 90);
                        web.setAlpha(0.01f);
                        root.addView(web, lp);
                        session.attachedRoot = root;
                    }
                }

                web.setWebViewClient(new WebViewClient() {
                    @Override public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                        try {
                            String candidate = request == null || request.getUrl() == null ? "" : request.getUrl().toString();
                            if (!session.audioBound && looksLikeTikTokMedia(candidate))
                                finishSuccess(session, view, candidate, bestPage(session, view), "", "");
                        } catch (Exception ignored) {}
                        return super.shouldInterceptRequest(view, request);
                    }

                    @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                        try {
                            String next = request == null || request.getUrl() == null ? "" : request.getUrl().toString();

                            // TikTok Lite / AppsFlyer OneLink can redirect WebView to a
                            // custom snssdk/tiktok URI. Extract the post ID before WebView
                            // rejects the non-HTTP scheme and continue on the web video page.
                            String canonicalNext = TikTokDeepLinkParser.canonicalFrom(next);
                            if (TikTokResolver.isCanonicalVideoPage(canonicalNext)) {
                                session.lastPage = canonicalNext;
                                view.loadUrl(canonicalNext, Collections.singletonMap("Referer", "https://www.tiktok.com/"));
                                maybeSwitchToPlayer(session, view, canonicalNext, 250L);
                                return true;
                            }

                            if (isHttp(next)) {
                                session.lastPage = next;
                                maybeSwitchToPlayer(session, view, next, 650L);
                                return false;
                            }

                            // Do not hand arbitrary custom schemes to the system.
                            return true;
                        } catch (Exception ignored) {}
                        return false;
                    }

                    @Override public void onPageFinished(WebView view, String pageUrl) {
                        super.onPageFinished(view, pageUrl);
                        session.lastPage = pageUrl == null ? "" : pageUrl;

                        if (TikTokResolver.needsResolution(pageUrl)) {
                            String embeddedPage = TikTokDeepLinkParser.canonicalFrom(pageUrl);
                            if (TikTokResolver.isCanonicalVideoPage(embeddedPage)) {
                                session.lastPage = embeddedPage;
                                view.loadUrl(embeddedPage, Collections.singletonMap("Referer", "https://www.tiktok.com/"));
                                maybeSwitchToPlayer(session, view, embeddedPage, 250L);
                                return;
                            }
                        }

                        probe(session, view, pageUrl, 0L);
                        probe(session, view, pageUrl, 250L);
                        probe(session, view, pageUrl, 700L);
                        probe(session, view, pageUrl, 1_500L);
                        probe(session, view, pageUrl, 3_000L);
                        maybeSwitchToPlayer(session, view, pageUrl, 850L);
                        if (pageUrl != null && pageUrl.contains("/player/v1/")) {
                            forceMutedPlayback(session, view, 100L);
                            forceMutedPlayback(session, view, 700L);
                            forceMutedPlayback(session, view, 1_600L);
                        }
                    }
                });

                web.loadUrl(canonical, Collections.singletonMap("Referer", "https://www.tiktok.com/"));
                // TikTok's canonical page can stall behind anti-bot UI. Start the official
                // player path independently so a real media request can still be observed.
                maybeSwitchToPlayer(session, web, canonical, 700L);
                MAIN.postDelayed(() -> finishFailure(session,
                        new IllegalStateException("TikTok browser/player session produced no media URL")), SESSION_HARD_TIMEOUT_MS);
            } catch (Throwable e) {
                finishFailure(session, e);
            }
        });
    }

    private static void maybeSwitchToPlayer(Session session, WebView web, String pageUrl, long delay) {
        String id = extractVideoId(pageUrl);
        if (id.isEmpty()) id = extractVideoId(session.canonical);
        if (id.isEmpty() || session.playerTried.get()) return;
        final String finalId = id;
        MAIN.postDelayed(() -> {
            if (session.done.get() || !session.playerTried.compareAndSet(false, true)) return;
            try {
                String player = "https://www.tiktok.com/player/v1/" + finalId +
                        "?autoplay=1&muted=1&controls=0&progress_bar=0&play_button=0&volume_control=0";
                session.lastPage = player;
                web.loadUrl(player, Collections.singletonMap("Referer", "https://www.tiktok.com/"));
                forceMutedPlayback(session, web, 250L);
                forceMutedPlayback(session, web, 800L);
                forceMutedPlayback(session, web, 1_800L);
                forceMutedPlayback(session, web, 3_500L);
            } catch (Throwable ignored) {}
        }, Math.max(0L, delay));
    }

    private static void forceMutedPlayback(Session session, WebView web, long delay) {
        MAIN.postDelayed(() -> {
            if (session.done.get()) return;
            try {
                web.evaluateJavascript(
                        "(function(){try{var v=document.querySelector('video');if(!v)return 'no-video';" +
                        "v.muted=true;v.setAttribute('muted','');v.setAttribute('playsinline','');" +
                        "var p=v.play();if(p&&p.catch)p.catch(function(){});return 'play-requested';" +
                        "}catch(e){return 'play-error';}})()", null);
            } catch (Throwable ignored) {}
        }, Math.max(0L, delay));
    }

    private static void probe(Session session, WebView web, String pageUrl, long delay) {
        MAIN.postDelayed(() -> {
            if (session.done.get()) return;
            try {
                String script=session.audioBound ? audioProbe(extractVideoId(session.canonical)) : PROBE_JS;
                web.evaluateJavascript(script, raw -> {
                    if (session.done.get()) return;
                    try {
                        String jsonText = decodeJsValue(raw);
                        if (jsonText.isEmpty()) return;
                        JSONObject o = new JSONObject(jsonText);
                        String media = o.optString("u", "");
                        String title = o.optString("t", "");
                        // Never accept an arbitrary HTTP resource. Older fallback code could
                        // accidentally capture a thumbnail/script URL and pass it to yt-dlp.
                        String verifiedId=o.optString("i","");
                        if (session.audioBound && !verifiedId.equals(TikTokAudioRecovery.pageId(session.canonical))) return;
                        if (looksLikeTikTokMedia(media)) finishSuccess(session, web, media, pageUrl, title, verifiedId);
                    } catch (Exception ignored) {}
                });
            } catch (Throwable ignored) {}
        }, Math.max(0L, delay));
    }

    private static void finishSuccess(Session session, WebView web, String mediaUrl, String pageUrl, String title,
                                      String verifiedId) {
        if (!looksLikeTikTokMedia(mediaUrl) || !session.done.compareAndSet(false, true)) return;
        MAIN.post(() -> {
            try {
                String actualPage = web == null ? pageUrl : web.getUrl();
                if (!isHttp(actualPage)) actualPage = bestPage(session, web);
                if (session.audioBound && (verifiedId.isEmpty()
                        || !verifiedId.equals(TikTokAudioRecovery.pageId(actualPage))
                        || !TikTokAudioRecovery.secureMedia(mediaUrl)))
                    throw new IllegalStateException("TikTok audio source identity mismatch");
                CookieManager cm = CookieManager.getInstance();
                String cookies = cm.getCookie("https://www.tiktok.com/");
                if ((cookies == null || cookies.trim().isEmpty()) && isHttp(actualPage)) cookies = cm.getCookie(actualPage);
                String ua = web == null ? UA : web.getSettings().getUserAgentString();
                Result r = new Result(mediaUrl, actualPage, cookies, ua, title,
                        session.audioBound ? key(session.canonical) : session.key, verifiedId);
                session.result = r;
                CACHE.put(session.key, new CacheEntry(r));
            } catch (Throwable e) {
                session.error = e;
            } finally {
                cleanup(session);
            }
        });
    }

    private static void finishFailure(Session session, Throwable e) {
        if (!session.done.compareAndSet(false, true)) return;
        session.error = e;
        MAIN.post(() -> cleanup(session));
    }

    private static void cleanup(Session session) {
        WebView v = session.view.getAndSet(null);
        if (v != null) {
            try { if (session.attachedRoot != null) session.attachedRoot.removeView(v); } catch (Exception ignored) {}
            try { v.stopLoading(); } catch (Exception ignored) {}
            try { v.loadUrl("about:blank"); } catch (Exception ignored) {}
            try { v.clearHistory(); } catch (Exception ignored) {}
            try { v.removeAllViews(); } catch (Exception ignored) {}
            try { v.destroy(); } catch (Exception ignored) {}
        }
        INFLIGHT.remove(session.key, session);
        session.latch.countDown();
    }

    private static Result cached(String key) {
        CacheEntry e = CACHE.get(key);
        if (e == null) return null;
        if (System.currentTimeMillis() - e.savedAt > CACHE_TTL_MS || e.result == null || !e.result.valid()) {
            CACHE.remove(key, e);
            return null;
        }
        return e.result;
    }

    private static String key(String url) {
        String id = extractVideoId(url);
        return id.isEmpty() ? LinkCapture.normalize(url == null ? "" : url) : "tiktok:" + id;
    }

    public static String extractVideoId(String url) {
        if (url == null) return "";
        Matcher m = VIDEO_ID.matcher(url);
        return m.find() ? m.group(1) : "";
    }

    public static boolean looksLikeTikTokMedia(String url) {
        if (!isHttp(url)) return false;
        String s = url.toLowerCase(Locale.ROOT);
        boolean knownCdn = s.contains("tiktokcdn") || s.contains("tiktokv.com") || s.contains("byteoversea") ||
                s.contains("byteicdn") || s.contains("ibytedtos") || s.contains("muscdn") ||
                s.contains("akamaized.net") || s.contains("bytecdn") || s.contains("bytevcloud") ||
                s.contains("ibyteimg") || s.contains("musical.ly");
        boolean strongVideo = s.contains("mime_type=video_mp4") || s.contains("mime_type%3dvideo_mp4") ||
                s.contains("/video/tos/") || s.contains("/tos-") || s.contains("video_mp4") ||
                s.contains(".mp4") || s.contains("play_addr") || s.contains("playaddr") ||
                s.contains("playwm") || s.contains("video/tos");
        boolean obviousNonMedia = s.matches(".*\\.(?:js|css|html?|json|xml|jpg|jpeg|png|webp|gif|svg|ico)(?:\\?.*)?$");
        boolean tikTokPage = s.contains("www.tiktok.com/") || s.contains("m.tiktok.com/") || s.contains("tiktok.com/player/");
        return !obviousNonMedia && strongVideo && (knownCdn || !tikTokPage);
    }

    private static String bestPage(Session s, WebView web) {
        try {
            String u = web == null ? "" : web.getUrl();
            if (isHttp(u) && !u.contains("/player/v1/")) return u;
        } catch (Exception ignored) {}
        if (isHttp(s.lastPage) && !s.lastPage.contains("/player/v1/")) return s.lastPage;
        return s.canonical;
    }

    private static boolean isHttp(String s) {
        if (s == null) return false;
        String x = s.trim().toLowerCase(Locale.ROOT);
        return x.startsWith("https://") || x.startsWith("http://");
    }

    private static String decodeJsValue(String raw) {
        if (raw == null || raw.equals("null") || raw.equals("undefined")) return "";
        try {
            Object v = new JSONTokener(raw).nextValue();
            return v instanceof String ? (String) v : String.valueOf(v);
        } catch (Exception e) {
            return raw;
        }
    }

    static String audioProbe(String id) {
        // Only addresses belonging to the exact item. A video element, intercepted
        // resource, soundtrack or recommendation is not source identity evidence.
        if (id==null || !id.matches("[0-9]{10,24}")) return "'{}'";
        return "(function(){var id='"+id+"',out='',nodes=0;" +
                "function good(s){if(typeof s!=='string')return false;try{var u=new URL(s);" +
                "if(u.protocol!=='https:'||u.username||u.password||(u.port&&u.port!=='443'))return false;" +
                "var h=u.hostname.toLowerCase(),ds=['tiktokcdn.com','tiktokcdn-us.com','tiktokv.com'," +
                "'byteoversea.com','byteicdn.com','ibytedtos.com','muscdn.com','akamaized.net'," +
                "'bytecdn.cn','bytevcloud.com','ibyteimg.com','musical.ly'];" +
                "if(!ds.some(function(d){return h===d||h.endsWith('.'+d)}))return false;" +
                "var p=u.pathname.toLowerCase();" +
                "if(/\\.(m3u8|mpd|html?|json|xml|js|css|jpg|jpeg|png|webp|gif|svg)$/.test(p))return false;" +
                "return p.indexOf('/video/')>=0||p.indexOf('/tos-')>=0||p.endsWith('.mp4')" +
                "||u.search.toLowerCase().indexOf('mime_type=video_mp4')>=0;}catch(e){return false;}}" +
                "function addr(x,d){if(!x||d>7||out)return;if(typeof x==='string'){if(good(x))out=x;return;}" +
                "if(Array.isArray(x)){for(var i=0;i<x.length&&i<25&&!out;i++)addr(x[i],d+1);return;}" +
                "if(typeof x==='object'){var ks=['url','src','urlList','UrlList','url_list','PlayAddr'," +
                "'playAddr','play_addr','downloadAddr','download','download_addr'];" +
                "for(var j=0;j<ks.length&&!out;j++)addr(x[ks[j]],d+1);}}" +
                "function walk(x,d){if(!x||typeof x!=='object'||d>12||nodes++>9000||out)return;" +
                "if(String(x.id||x.aweme_id||'')===id&&x.video){" +
                "var v=x.video;addr(v.playAddr,0);addr(v.play_addr,0);addr(v.downloadAddr,0);" +
                "addr(v.download_addr,0);addr(v.download,0);addr(v.bitrateInfo,0);}" +
                "var keys=Object.keys(x);for(var j=0;j<keys.length&&j<180&&!out;j++)walk(x[keys[j]],d+1);}" +
                "var ids=['__UNIVERSAL_DATA_FOR_REHYDRATION__','__NEXT_DATA__','SIGI_STATE','sigi-persisted-data'];" +
                "for(var z=0;z<ids.length&&!out;z++){var el=document.getElementById(ids[z]);" +
                "if(el)try{walk(JSON.parse(el.textContent),0);}catch(e){}}" +
                "return JSON.stringify({u:out,i:out?id:'',t:document.title||''});})()";
    }

    private static final String PROBE_JS =
            "(function(){try{" +
            "var c=[],seen={},nodes=0;" +
            "function good(x){if(!x||typeof x!=='string'||x.indexOf('http')!==0)return false;var s=x.toLowerCase();" +
            "var h=s.indexOf('tiktokcdn')>=0||s.indexOf('tiktokv.com')>=0||s.indexOf('byteoversea')>=0||s.indexOf('byteicdn')>=0||s.indexOf('ibytedtos')>=0||s.indexOf('muscdn')>=0||s.indexOf('akamaized.net')>=0||s.indexOf('bytecdn')>=0||s.indexOf('bytevcloud')>=0||s.indexOf('musical.ly')>=0;" +
            "var p=s.indexOf('mime_type=video_mp4')>=0||s.indexOf('mime_type%3dvideo_mp4')>=0||s.indexOf('/video/tos/')>=0||s.indexOf('/tos-')>=0||s.indexOf('video_mp4')>=0||s.indexOf('.mp4')>=0||s.indexOf('play_addr')>=0||s.indexOf('playaddr')>=0||s.indexOf('playwm')>=0;var tp=s.indexOf('www.tiktok.com/')>=0||s.indexOf('m.tiktok.com/')>=0||s.indexOf('tiktok.com/player/')>=0;return p&&(h||!tp);}" +
            "function add(x){if(good(x)&&!seen[x]){seen[x]=1;c.push(x);}}" +
            "function take(x,d){if(!x||d>8||nodes++>9000)return;if(typeof x==='string'){add(x);return;}if(Array.isArray(x)){for(var i=0;i<x.length&&i<120;i++)take(x[i],d+1);return;}if(typeof x==='object'){" +
            "if(x.playAddr)take(x.playAddr,d+1);if(x.downloadAddr)take(x.downloadAddr,d+1);if(x.urlList)take(x.urlList,d+1);if(x.UrlList)take(x.UrlList,d+1);if(x.url_list)take(x.url_list,d+1);" +
            "var ks=Object.keys(x);for(var j=0;j<ks.length&&j<160;j++)take(x[ks[j]],d+1);}}" +
            "var v=document.querySelector('video');if(v){try{v.muted=true;}catch(e){}add(v.currentSrc);add(v.src);}" +
            "var ss=document.querySelectorAll('video source');for(var i=0;i<ss.length;i++)add(ss[i].src);" +
            "var ms=document.querySelectorAll('meta[property=\"og:video\"],meta[property=\"og:video:url\"],meta[property=\"og:video:secure_url\"]');for(var m=0;m<ms.length;m++)add(ms[m].content);" +
            "try{var pe=performance.getEntriesByType('resource');for(var r=0;r<pe.length;r++)add(pe[r].name);}catch(e){}" +
            "var ids=['__UNIVERSAL_DATA_FOR_REHYDRATION__','SIGI_STATE','sigi-persisted-data'];for(var z=0;z<ids.length;z++){var el=document.getElementById(ids[z]);if(el&&el.textContent){try{take(JSON.parse(el.textContent),0);}catch(e){}}}" +
            "return JSON.stringify({u:c.length?c[0]:'',t:document.title||''});" +
            "}catch(e){return JSON.stringify({u:'',t:''});}})()";
}
