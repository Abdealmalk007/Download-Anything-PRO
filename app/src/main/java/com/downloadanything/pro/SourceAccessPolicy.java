package com.downloadanything.pro;
import java.util.Locale;
/** Source eligibility is not a media downloader failure.
 * Never assume session cookies grant access to another person's private media.
 */
public final class SourceAccessPolicy {
    private SourceAccessPolicy() {}
    public static boolean isAudienceRestricted(String raw) {
        String s = raw == null ? "" : raw.toLowerCase(Locale.ROOT);
        return s.contains("this content isn't available to everyone")
            || s.contains("this content isn’t available to everyone")
            || s.contains("can't be seen by certain audiences")
            || s.contains("cannot be seen by certain audiences")
            || s.contains("not available to everyone")
            || s.contains("only available to certain audiences")
            || s.contains("not available to your account")
            || s.contains("this account is private")
            || s.contains("private post");
    }
}
