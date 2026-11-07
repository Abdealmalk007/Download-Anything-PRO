package com.downloadanything.pro;

import android.content.Context;
import java.util.Locale;

public final class ErrorFormatter {
    private ErrorFormatter() {}

    public static String friendly(Context c, String raw) {
        boolean ar = UiPrefs.isArabic(c);
        String s = raw == null ? "" : raw.toLowerCase(Locale.ROOT);

        // A source's audience permissions cannot be bypassed by retrying or changing quality.
        if (SourceAccessPolicy.isAudienceRestricted(raw)) {
            return ar
                ? "هذا المقطع مقيّد في Instagram ولا يتاح لجميع الحسابات. افتح المصدر وسجّل الدخول بحساب لديه إذن مشاهدة المقطع، ثم حاول مجددًا. إذا لم يسمح Instagram بعرضه لحسابك فلن يتمكن التطبيق من تنزيله."
                : "Instagram restricts this post to certain viewers. Open the source and sign in to an account authorized to view it, then retry. If Instagram denies access to your account, this app cannot download it.";
        }
        // Diagnose filesystem failure before site-specific failures; no sign-in can fix errno 30.
        if (s.contains("read-only file system") || s.contains("errno 30") || s.contains("temporary directory")) {
            return ar ? "تعذر الكتابة في المجلد المؤقت لمحرك التنزيل. حدث التطبيق إلى 5.5.0 وأعد المحاولة؛ لا تتطلب هذه المشكلة تسجيل الدخول."
                      : "The download engine cannot write its temporary files. Update to 5.5.0 and retry; signing in will not fix this.";
        }
        if (s.contains("play.google.com/store") || s.contains("market.android.com")) {
            return ar ? "وصل رابط متجر التطبيقات بدل رابط الفيديو. افتح المقطع في TikTok وانسخ رابطه الأصلي من مشاركة؛ لا يمكن استخراج فيديو غير محدد من رابط المتجر وحده."
                    : "An app store URL was received instead of a video link. Copy the clip's original TikTok share link; the store page alone does not identify a video.";
        }
        if (s.contains("[facebook]") && s.contains("cannot parse data")) {
            return ar ? "تعذر على محرك Facebook قراءة بيانات هذا الفيديو. جُرّب مسار بديل لنفس معرّف المقطع إن كان متاحًا؛ قد يكون المصدر غيّر واجهته أو قيّد الوصول. جرّب رابط الفيديو العام الأصلي."
                    : "Facebook's extractor could not parse this video. A same-video-ID recovery was attempted when available; the source may have changed or restricted access.";
        }
        // 4.9.4 TikTok live-diagnostics friendly taxonomy. Keep technical text behind details.
        if (s.contains("tiktok") && (s.contains("403") || s.contains("forbidden")))
            return ar ? "رفض TikTok الجلسة الحالية. افتح TikTok في المتصفح المدمج وحدّث الصفحة، ثم أعد المحاولة يدويًا." : "TikTok rejected the current session. Open TikTok in the built-in browser, refresh the page, then retry manually.";
        if (s.contains("facebook share link could not be resolved"))
            return ar ? "لم أتمكن من تحويل رابط مشاركة Facebook إلى رابط الفيديو الأصلي. افتح المنشور العام في المتصفح ثم انسخ رابطه المباشر وأعد المحاولة." : "Facebook share link could not be resolved to a video post. Open the public post and copy its direct video/reel link.";
        if (s.contains("tiktok") && (s.contains("404") || s.contains("not found") || s.contains("unavailable")))
            return ar ? "لم يتمكن محرك التنزيل من الوصول إلى فيديو TikTok من هذا الرابط. قد يكون السبب انتهاء الرابط أو حذف المقطع أو قيود المنصة؛ لا يمكن تأكيد السبب دون تفاصيل الطلب." : "TikTok media could not be accessed through this link. It could be a stale link, missing post or source restriction; the exact cause requires request diagnostics.";
        if (s.contains("tiktok") && (s.contains("private") || s.contains("login required") || s.contains("sign in")))
            return ar ? "فيديو TikTok خاص أو يحتاج جلسة تسجيل دخول صالحة." : "This TikTok video is private or requires a valid signed-in session.";
        if (s.contains("tiktok") && (s.contains("short link") || s.contains("resolve") || s.contains("redirect") ||
                s.contains("unsupported url") || s.contains("tiktoklite.onelink.me")))
            return ar ? "تعذر تجهيز رابط TikTok المختصر تلقائيًا. سيحاول التطبيق مسار TikTok Lite/المتصفح قبل بدء التنزيل؛ إذا استمر الخطأ افتح المقطع ثم شاركه من TikTok من جديد."
                    : "The TikTok short link could not be prepared automatically. The app will try the TikTok Lite/browser recovery path before downloading; if it persists, open the video and share it again from TikTok.";
        if (s.contains("tiktok") && (s.contains("verification") || s.contains("missing its audio") || s.contains("no playable")))
            return ar ? "تم استلام بيانات من TikTok لكن الملف الناتج لم يجتز فحص الفيديو والصوت، لذلك لم يُنشر." : "TikTok returned media, but the result failed video/audio verification and was not published.";

        if (s.contains("tiktok browser fallback") || s.contains("tiktok browser session") ||
                s.contains("impersonation") || s.contains("status code 0") ||
                s.contains("universal data") || (s.contains("tiktok") && s.contains("unable to extract"))) {
            return ar
                    ? "تعذر TikTok بعد محاولات الاستخراج والمشغل الرسمي والتنزيل المباشر. أعد مشاركة المقطع وهو مفتوح في TikTok؛ قد يكون المقطع خاصًا أو مقيّدًا أو يحتاج جلسة صالحة."
                    : "TikTok failed after extractor, official-player and direct-download attempts. Share the clip again while it is open in TikTok; it may be private, restricted, or require a valid session.";
        }
        if (s.contains("cdn did not return a final video") || s.contains("invalid cdn content-range"))
            return ar ? "رابط الوسائط المؤقت من TikTok لم يعد صالحًا. سيعيد التطبيق تجهيز المصدر قبل اختيار مسار التنزيل." : "The temporary TikTok media URL is stale. The app will refresh the source before choosing a download path.";
        if (s.contains("unable to open for writing") || s.contains("no such file or directory"))
            return ar ? "تعذر إنشاء الملف المؤقت. تحقق من المساحة المتاحة ومن إذن التخزين ثم أعد المحاولة." : "The temporary file could not be created. Check free space and storage access, then retry.";
        if (s.contains("login required") || s.contains("sign in") || s.contains("requires a valid session") || s.contains("login to confirm"))
            return ar ? "هذا المصدر يتطلب جلسة تسجيل دخول صالحة. اضغط «فتح المصدر»، سجّل الدخول في المتصفح المدمج، ثم ارجع واضغط إعادة المحاولة." : "This source requires a valid signed-in session. Tap Open source, sign in in the built-in browser, then return and Retry.";
        if (s.contains("source identity changed before download"))
            return ar ? "تغير مُعرّف المصدر أو رابط المنشور بين الفحص والتنزيل. أعد تحليل الرابط؛ لن ينزّل التطبيق فيديو مختلفًا." : "The selected media identity changed between analysis and download. Re-analyze the link; a different post will never be substituted.";
        if (s.contains("cookies") || s.contains("instagram") && s.contains("unable to extract"))
            return ar ? "تعذر استخراج الوسائط العامة حالياً. قد يكون الموقع يقيّد الطلبات أو يتطلب جلسة للمقطع المحدد؛ جرّب رابط منشور عام جديد أولاً." : "Cannot extract public media right now. The site may throttle requests or require a session for this post; try another public post first.";
        if (s.contains("requested format is not available") || s.contains("format unavailable"))
            return ar ? "هذه الجودة لم تعد متاحة من المصدر. اختر جودة أخرى من نتائج التحليل." : "This quality is no longer available from the source. Choose another quality from analysis results.";
        if (s.contains("media verification failed") || s.contains("final publish failed") || s.contains("final media could not be identified"))
            return ar ? "اكتمل تنزيل الوسائط، لكن تعذر نشر النسخة العامة. سيحافظ التطبيق على سلامة الملف ويعيد محاولة النشر تلقائيًا." : "The media download completed, but publishing the public copy failed. The app will preserve the verified media and retry publishing safely.";
        if (s.contains("no playable media file"))
            return ar ? "انتهت المحاولة دون إنتاج ملف وسائط قابل للتشغيل." : "The attempt finished without producing a playable media file.";
        if (s.contains("timeout") || s.contains("timed out"))
            return ar ? "انتهت مهلة الاتصال. تحقق من الشبكة ثم أعد المحاولة." : "The connection timed out. Check the network and retry.";
        if (s.contains("403") || s.contains("forbidden"))
            return ar ? "رفض الموقع طلب التنزيل. سيحاول التطبيق مسار التوافق عند توفره." : "The site rejected the request. The app will use a compatibility path when available.";
        if (s.contains("wifi") && (s.contains("waiting") || s.contains("required")))
            return ar ? "التنزيل بانتظار Wi‑Fi وسيستأنف عند عودة الشبكة المناسبة." : "The download is waiting for Wi-Fi and will resume when an allowed network returns.";
        if (s.contains("merge") || s.contains("ffmpeg"))
            return ar ? "فشل دمج الفيديو والصوت. يمكنك إعادة المحاولة، ولن يعتبر الملف مكتملًا قبل التحقق." : "Video/audio merge failed. Retry is available, and the file will not be marked complete before verification.";
        if (s.contains("network") || s.contains("connection") || s.contains("offline"))
            return ar ? "انقطع الاتصال بالإنترنت. سيحاول التطبيق الاستكمال عند عودة الشبكة إذا كانت إعادة المحاولة مفعلة." : "The internet connection was interrupted. The app will resume when possible if automatic retry is enabled.";
        if (raw == null || raw.trim().isEmpty())
            return ar ? "حدث خطأ غير معروف أثناء التنزيل." : "An unknown download error occurred.";
        return raw.length() > 420 ? raw.substring(0, 420) + "…" : raw;
    }
}
