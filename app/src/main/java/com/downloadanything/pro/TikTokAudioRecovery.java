package com.downloadanything.pro;

import java.net.URI;
import java.util.*;

/** Source-bound, short-lived progressive candidates. Never stores files from other jobs. */
final class TikTokAudioRecovery {
    static final class Candidate {
        final String sourceId, pageUrl, mediaUrl, cookies, userAgent;
        Candidate(String id, String page, String media, String cookie, String ua) {
            sourceId = text(id); pageUrl = text(page); mediaUrl = text(media);
            cookies = text(cookie); userAgent = text(ua);
        }
        boolean matches(String id, String page) {
            return !text(id).isEmpty() && sourceId.equals(id) && id.equals(pageId(page))
                    && id.equals(pageId(pageUrl)) && secureMedia(mediaUrl);
        }
    }
    interface Access {
        void check() throws Exception;
        boolean preflight(Candidate candidate) throws Exception;
        Candidate refetch() throws Exception;
    }
    private static final class Entry {
        final long at;
        final List<Candidate> candidates;
        Entry(long at, List<Candidate> candidates) { this.at=at; this.candidates=candidates; }
    }
    private static final Map<String, Entry> CACHE = new LinkedHashMap<>();
    private TikTokAudioRecovery() {}

    // No transfer is attempted here. One refetch and at most five header probes.
    static Candidate choose(String id, String page, List<Candidate> cached, Candidate warm, Access access)
            throws Exception {
        access.check();
        if (text(id).isEmpty() || !id.equals(pageId(page))) return null;
        int probes=0;
        Set<String> seen=new HashSet<>();
        for (Candidate candidate : cached) {
            if (probes>=3) break;
            if (candidate==null || !candidate.matches(id,page) || !seen.add(candidate.mediaUrl)) continue;
            probes++;
            access.check();
            if (access.preflight(candidate)) { access.check(); return candidate; }
        }
        if (warm!=null && warm.matches(id,page) && seen.add(warm.mediaUrl)) {
            access.check();
            if (access.preflight(warm)) { access.check(); return warm; }
        }
        access.check();
        Candidate fresh=access.refetch();
        access.check();
        if (fresh!=null && fresh.matches(id,page) && access.preflight(fresh)) {
            access.check();
            return fresh;
        }
        return null;
    }

    static boolean supports(String format, String quality, String audioId, String videoId,
                            boolean metadata, boolean thumbnail, String subtitles) {
        String f=text(format).toLowerCase(Locale.ROOT);
        if (f.isEmpty()) f="m4a";
        String q=text(quality);
        boolean rate=q.isEmpty() || q.equalsIgnoreCase("best");
        if (q.matches("[0-9]{2,3}")) {
            int n=Integer.parseInt(q);
            rate=n>=64 && n<=320;
        }
        return (f.equals("m4a") || f.equals("mp3")) && rate && text(audioId).isEmpty()
                && text(videoId).isEmpty() && !metadata && !thumbnail
                && (text(subtitles).isEmpty() || subtitles.equals("none"));
    }

    /** Best M4A copies AAC without re-encoding; other requests retain their exact output setting. */
    static List<String> codecOptions(String format, String quality) {
        if (!supports(format,quality,"","",false,false,"none"))
            throw new IllegalArgumentException("Unsupported TikTok local audio selection");
        String f=text(format).isEmpty() ? "m4a" : format.toLowerCase(Locale.ROOT);
        boolean best=text(quality).isEmpty() || quality.equalsIgnoreCase("best");
        List<String> args=new ArrayList<>(Arrays.asList("-map","0:a:0","-vn"));
        if (f.equals("m4a")) {
            args.addAll(Arrays.asList("-c:a",best ? "copy" : "aac"));
            if (!best) args.addAll(Arrays.asList("-b:a",quality+"k"));
            args.addAll(Arrays.asList("-movflags","+faststart"));
        } else {
            args.addAll(Arrays.asList("-c:a","libmp3lame",best ? "-q:a" : "-b:a",best ? "0" : quality+"k"));
        }
        return args;
    }

    static synchronized void remember(Object info, String requestedPage, String cookies, String ua, long now) {
        String id=text(invoke(info,"getId"));
        String page=text(invoke(info,"getWebpageUrl"));
        String requestedId=pageId(requestedPage);
        if (!id.matches("[0-9]{10,24}") || !id.equals(pageId(page))
                || (!requestedId.isEmpty() && !id.equals(requestedId)) || !tikTokPage(requestedPage)) return;
        Object formats=invoke(info,"getFormats");
        // Fresh metadata supersedes an earlier snapshot, including a known silent result.
        CACHE.remove(id);
        if (!(formats instanceof Iterable)) return;
        List<Object> sorted=new ArrayList<>();
        for (Object f : (Iterable<?>)formats) sorted.add(f);
        sorted.sort((a,b)->Double.compare(number(invoke(b,"getTbr")),number(invoke(a,"getTbr"))));
        List<Candidate> candidates=new ArrayList<>();
        for (Object f : sorted) {
            // Only real progressive AAC+video formats qualify for the lossless M4A path.
            String ac=text(invoke(f,"getAcodec")).toLowerCase(Locale.ROOT);
            String vc=text(invoke(f,"getVcodec"));
            String protocol=text(invoke(f,"getProtocol"));
            // Absent codec metadata is unknown, not proof of a silent stream. The
            // downloaded file is still verified for a real audio track before use.
            if (!(ac.isEmpty() || ac.equals("unknown") || ac.startsWith("mp4a") || ac.equals("aac"))
                    || vc.equalsIgnoreCase("none")
                    || !text(invoke(f,"getExt")).equalsIgnoreCase("mp4")
                    || !(protocol.isEmpty() || protocol.equals("https"))) continue;
            Candidate c=new Candidate(id,page,text(invoke(f,"getUrl")),cookies,ua);
            if (c.matches(id,page)) candidates.add(c);
            if (candidates.size()==3) break;
        }
        if (candidates.isEmpty()) return;
        CACHE.put(id,new Entry(now,candidates));
        while (CACHE.size()>16) CACHE.remove(CACHE.keySet().iterator().next());
    }

    static synchronized List<Candidate> cached(String id, String page, long now) {
        Entry e=CACHE.get(id);
        if (e==null || now<e.at || now-e.at>90_000L) { CACHE.remove(id); return Collections.emptyList(); }
        List<Candidate> found=new ArrayList<>();
        for (Candidate c:e.candidates) if (c.matches(id,page)) found.add(c);
        return found;
    }

    static String pageId(String page) {
        if (!tikTokPage(page)) return "";
        try {
            java.util.regex.Matcher m=java.util.regex.Pattern.compile(
                    "/(?:@[^/]+/video|player/v1|embed/v2)/([0-9]{10,24})(?:/|$)").matcher(new URI(page).getPath());
            return m.find() ? m.group(1) : "";
        } catch (Exception ignored) { return ""; }
    }
    private static boolean tikTokPage(String page) {
        try {
            URI u=new URI(text(page));
            return "https".equalsIgnoreCase(u.getScheme()) && u.getRawUserInfo()==null
                    && (u.getPort()==-1 || u.getPort()==443) && domain(u.getHost(),"tiktok.com");
        } catch (Exception ignored) { return false; }
    }
    static boolean secureMedia(String url) {
        try {
            URI u=new URI(text(url));
            if (!"https".equalsIgnoreCase(u.getScheme()) || u.getRawUserInfo()!=null
                    || (u.getPort()!=-1 && u.getPort()!=443)) return false;
            String path=text(u.getPath()).toLowerCase(Locale.ROOT);
            if (path.endsWith(".m3u8") || path.endsWith(".mpd") || path.endsWith(".html")
                    || path.endsWith(".htm") || path.endsWith(".json") || path.endsWith(".xml")
                    || path.endsWith(".js") || path.endsWith(".css")) return false;
            for (String d:Arrays.asList("tiktokcdn.com","tiktokcdn-us.com","tiktokv.com",
                    "byteoversea.com","byteicdn.com","ibytedtos.com","muscdn.com",
                    "akamaized.net","bytecdn.cn","bytevcloud.com","ibyteimg.com","musical.ly"))
                if (domain(u.getHost(),d)) return true;
        } catch (Exception ignored) {}
        return false;
    }
    private static boolean domain(String host,String domain) {
        String h=text(host).toLowerCase(Locale.ROOT);
        return h.equals(domain) || h.endsWith("."+domain);
    }
    private static Object invoke(Object target,String method) {
        try { return target.getClass().getMethod(method).invoke(target); }
        catch (Exception ignored) { return null; }
    }
    private static double number(Object value) {
        try { return Double.parseDouble(text(value)); } catch (Exception ignored) { return 0; }
    }
    private static String text(Object value) { return value==null ? "" : value.toString().trim(); }
}
