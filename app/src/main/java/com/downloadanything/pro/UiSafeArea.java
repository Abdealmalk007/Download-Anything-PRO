package com.downloadanything.pro;

import android.app.Activity;
import android.view.View;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowCompat;

/** Non-consuming system-bar safe area. Avoid covering Android navigation/home gestures.
 *  Preserve any intrinsic container padding so RTL and theme spacing are unchanged.
 */
public final class UiSafeArea {
    private UiSafeArea(){}
    public static void apply(Activity activity, View root){
        // Explicit edge-to-edge guarantees a single consistent inset strategy on Android 7-16.
        WindowCompat.setDecorFitsSystemWindows(activity.getWindow(),false);
        final int l=root.getPaddingLeft(),t=root.getPaddingTop(),
                r=root.getPaddingRight(),b=root.getPaddingBottom();
        // Retain the largest valid nav inset within the same Activity root.
        // Transient navigation overlays may temporarily report zero while tabs change.
        final int[] stableBottomInset={0};
        ViewCompat.setOnApplyWindowInsetsListener(root,(view,windowInsets)->{
            Insets system=windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            // Include hidden/temporary three-button bars and transient gesture overlays.
            Insets nav=windowInsets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars());
            Insets gestures=windowInsets.getInsets(WindowInsetsCompat.Type.systemGestures());
            int safeBottom=Math.max(system.bottom,Math.max(nav.bottom,gestures.bottom));
            stableBottomInset[0]=Math.max(stableBottomInset[0],safeBottom);
            int breathingRoom=(int)(18f*activity.getResources().getDisplayMetrics().density+0.5f);
            view.setPadding(l+system.left,t+system.top,r+system.right,b+stableBottomInset[0]+breathingRoom);
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(root);
    }
}
