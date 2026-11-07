package com.downloadanything.pro;

/** Explicit audio selection from Android Sharesheet. */
public final class ShareAudioReceiverActivity extends ExplicitShareActivity {
    @Override protected boolean audioOnly() { return true; }
}
