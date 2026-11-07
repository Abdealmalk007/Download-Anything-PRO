package com.downloadanything.pro;
import android.content.Intent;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
/** Backward-compatible entry point: the Tools tab is exclusively the audio converter.
 * Keep this class/manifest entry so saved shortcuts resolve, without displaying old tools.
 */
public final class QuickToolsActivity extends AppCompatActivity {
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Intent next=new Intent(this,AudioConverterActivity.class);
        next.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        startActivity(next);
        finish();
    }
}
