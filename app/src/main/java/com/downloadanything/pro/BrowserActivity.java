package com.downloadanything.pro;

import android.content.*;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;

/**
 * In-app browser with native media actions:
 * Analyze, Background Listen and Download.
 */
public class BrowserActivity extends AppCompatActivity {
    private WebView web;
    private EditText address;
    private Button listenAction,downloadAction,analyzeAction;
    private TextView mediaStatus;
    private String detectedMediaUrl="";
    private String currentPageUrl="";
    private String currentUserAgent="";
    private boolean arabic,resolvingBackground;
    private int bg,card,input,text,muted,accent,outline;

    @Override protected void onCreate(Bundle b){
        super.onCreate(b);
        arabic=UiPrefs.isArabic(this); UiPrefs.Palette p=UiPrefs.palette(this);
        bg=p.bg;card=p.card;input=p.input;text=p.text;muted=p.muted;accent=p.gold;outline=p.outline;
        getWindow().setStatusBarColor(bg);getWindow().setNavigationBarColor(bg);
        setContentView(build());
        String u=getIntent().getStringExtra("start_url");
        if(u==null||u.trim().isEmpty())u="https://www.google.com";
        load(u);
    }

    private View build(){
        LinearLayout shell=new LinearLayout(this);shell.setOrientation(LinearLayout.VERTICAL);shell.setBackgroundColor(bg);
        shell.setLayoutDirection(arabic?View.LAYOUT_DIRECTION_RTL:View.LAYOUT_DIRECTION_LTR);
        UiSafeArea.apply(this,shell);

        LinearLayout bar=new LinearLayout(this);bar.setGravity(Gravity.CENTER_VERTICAL);bar.setPadding(dp(6),dp(6),dp(6),dp(4));
        Button home=btn("⌂"),back=btn("‹"),refresh=btn("↻");
        address=new EditText(this);address.setSingleLine(true);address.setTextColor(text);address.setHintTextColor(muted);address.setTextSize(13);
        address.setHint(t("أدخل رابط الموقع","Enter website URL"));address.setPadding(dp(12),0,dp(12),0);
        android.graphics.drawable.GradientDrawable ag=new android.graphics.drawable.GradientDrawable();
        ag.setColor(input);ag.setCornerRadius(dp(13));ag.setStroke(dp(1),outline);address.setBackground(ag);
        Button go=btn(t("فتح","Go"));
        bar.addView(home,new LinearLayout.LayoutParams(dp(48),dp(48)));bar.addView(back,new LinearLayout.LayoutParams(dp(48),dp(48)));
        bar.addView(address,new LinearLayout.LayoutParams(0,dp(48),1f));bar.addView(go,new LinearLayout.LayoutParams(dp(64),dp(48)));bar.addView(refresh,new LinearLayout.LayoutParams(dp(48),dp(48)));
        shell.addView(bar,new LinearLayout.LayoutParams(-1,-2));

        HorizontalScrollView quickScroll=new HorizontalScrollView(this);quickScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout quick=new LinearLayout(this);quick.setGravity(Gravity.CENTER_VERTICAL);quick.setPadding(dp(6),0,dp(6),dp(5));
        String[] names={"YouTube","TikTok","Facebook","Instagram","X","Vimeo","Reddit","Dailymotion","SoundCloud","Twitch"};
        String[] urls={"https://m.youtube.com","https://www.tiktok.com","https://m.facebook.com","https://www.instagram.com","https://x.com","https://vimeo.com","https://www.reddit.com","https://www.dailymotion.com","https://soundcloud.com","https://www.twitch.tv"};
        for(int i=0;i<names.length;i++){final String dest=urls[i];Button q=btn(names[i]);q.setTextSize(12);q.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);PlatformVisuals.apply(q,names[i],outline,UiPrefs.isDark(this));q.setOnClickListener(v->load(dest));LinearLayout.LayoutParams qp=new LinearLayout.LayoutParams(dp(names[i].length()>9?118:96),dp(48));qp.setMarginEnd(dp(5));quick.addView(q,qp);}
        quickScroll.addView(quick,new HorizontalScrollView.LayoutParams(-2,-2));shell.addView(quickScroll,new LinearLayout.LayoutParams(-1,dp(54)));

        web=new WebView(this);web.setBackgroundColor(bg);
        WebSettings ws=web.getSettings();ws.setJavaScriptEnabled(true);ws.setDomStorageEnabled(true);ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setUserAgentString(ws.getUserAgentString()+" DownloadAnythingPRO/"+BuildConfig.VERSION_NAME);ws.setAllowFileAccess(false);ws.setAllowContentAccess(true);currentUserAgent=ws.getUserAgentString();
        CookieManager.getInstance().setAcceptCookie(true);try{CookieManager.getInstance().setAcceptThirdPartyCookies(web,true);}catch(Exception ignored){}

        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){return false;}
            @Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest r){
                if(r!=null&&r.getUrl()!=null)maybeDetect(r.getUrl().toString(),r.getRequestHeaders()==null?"":r.getRequestHeaders().get("Accept"));
                return super.shouldInterceptRequest(v,r);
            }
            @Override public void onPageStarted(WebView v,String u,android.graphics.Bitmap favicon){
                currentPageUrl=u==null?"":u;detectedMediaUrl="";updateMediaActions();
            }
            @Override public void onPageFinished(WebView v,String u){
                address.setText(u);currentPageUrl=u==null?"":u;updateMediaActions();
            }
        });
        web.setWebChromeClient(new WebChromeClient());
        web.setDownloadListener((url,userAgent,contentDisposition,mimetype,contentLength)->{
            currentUserAgent=userAgent==null?currentUserAgent:userAgent;offerDetected(url,mimetype);
        });
        shell.addView(web,new LinearLayout.LayoutParams(-1,0,1f));

        LinearLayout mediaBar=new LinearLayout(this);mediaBar.setGravity(Gravity.CENTER_VERTICAL);mediaBar.setPadding(dp(8),dp(6),dp(8),dp(8));
        mediaStatus=new TextView(this);mediaStatus.setTextColor(muted);mediaStatus.setTextSize(12);mediaStatus.setPadding(dp(3),dp(2),dp(3),dp(5));
        LinearLayout actions=new LinearLayout(this);actions.setGravity(Gravity.CENTER);
        analyzeAction=btn(t("🔎 تحليل","🔎 Analyze"));listenAction=btn(t("🎧 استماع","🎧 Listen"));downloadAction=btn(t("⬇ تنزيل","⬇ Download"));
        for(Button b:new Button[]{analyzeAction,listenAction,downloadAction}){b.setTextSize(13);b.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);}
        analyzeAction.setOnClickListener(v->analyzeCurrent());
        listenAction.setOnClickListener(v->listenInBackground());
        downloadAction.setOnClickListener(v->downloadCurrent());
        LinearLayout.LayoutParams analyzeLp=new LinearLayout.LayoutParams(0,dp(52),1f);
        LinearLayout.LayoutParams listenLp=new LinearLayout.LayoutParams(0,dp(52),1f);
        LinearLayout.LayoutParams downloadLp=new LinearLayout.LayoutParams(0,dp(52),1f);
        listenLp.setMarginStart(dp(6));downloadLp.setMarginStart(dp(6));
        actions.addView(analyzeAction,analyzeLp);
        actions.addView(listenAction,listenLp);
        actions.addView(downloadAction,downloadLp);
        LinearLayout mediaWrap=new LinearLayout(this);mediaWrap.setOrientation(LinearLayout.VERTICAL);
        mediaWrap.addView(mediaStatus,new LinearLayout.LayoutParams(-1,-2));mediaWrap.addView(actions,new LinearLayout.LayoutParams(-1,dp(54)));
        mediaBar.addView(mediaWrap,new LinearLayout.LayoutParams(-1,-2));shell.addView(mediaBar);
        shell.addView(PrimaryBottomNav.build(this,PageSwipeNavigator.BROWSER),new LinearLayout.LayoutParams(-1,dp(PrimaryBottomNav.HEIGHT_DP)));

        home.setOnClickListener(v->load("https://www.google.com"));
        back.setOnClickListener(v->{if(web.canGoBack())web.goBack();else finish();});
        refresh.setOnClickListener(v->web.reload());
        go.setOnClickListener(v->load(address.getText().toString()));
        address.setOnEditorActionListener((v,a,e)->{load(address.getText().toString());return true;});
        updateMediaActions();
        return shell;
    }

    private String currentTarget(){
        String page=currentPageUrl==null||currentPageUrl.isEmpty()?web.getUrl():currentPageUrl;
        String target=BrowserMediaDetector.analysisTarget(page,detectedMediaUrl);
        return target==null||target.trim().isEmpty()?page:target;
    }

    private String currentCookies(String target){
        try{return CookieManager.getInstance().getCookie(target);}catch(Exception e){return "";}
    }

    private String cookieFile(String target){
        String cookies=currentCookies(target);
        return CookieBridge.writeCookieFile(this,target,cookies==null?"":cookies);
    }

    private void analyzeCurrent(){
        String target=currentTarget();if(!LinkCapture.isHttp(target))return;
        String cf=cookieFile(target);
        Intent i=new Intent(this,MainActivity.class).setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,target);
        if(cf!=null&&!cf.isEmpty())i.putExtra("browserCookieFile",cf);
        if(currentUserAgent!=null&&!currentUserAgent.isEmpty())i.putExtra("browserUserAgent",currentUserAgent);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);startActivity(i);
    }

    private void downloadCurrent(){
        String target=currentTarget();if(!LinkCapture.isHttp(target))return;
        String cf=cookieFile(target);
        Intent i=new Intent(this,UniversalDownloadActivity.class).putExtra(UniversalDownloadActivity.EXTRA_URL,target)
                .putExtra(UniversalDownloadActivity.EXTRA_COOKIE_FILE,cf==null?"":cf)
                .putExtra(UniversalDownloadActivity.EXTRA_USER_AGENT,currentUserAgent==null?"":currentUserAgent);
        startActivity(i);
    }

    private void listenInBackground(){
        if(resolvingBackground)return;
        String target=currentTarget();if(!LinkCapture.isHttp(target))return;
        resolvingBackground=true;listenAction.setEnabled(false);
        mediaStatus.setText(t("جارٍ تجهيز الصوت الحقيقي من المصدر…","Resolving a real source audio stream…"));
        String cookies=currentCookies(target);String cf=CookieBridge.writeCookieFile(this,target,cookies==null?"":cookies);
        BrowserBackgroundResolver.resolve(this,target,cf,currentUserAgent,(r,e)->runOnUiThread(()->{
            resolvingBackground=false;listenAction.setEnabled(true);
            if(e!=null||r==null){
                mediaStatus.setText(ErrorFormatter.friendly(this,e==null?"Background playback unavailable":String.valueOf(e.getMessage())));
                CookieBridge.delete(cf);return;
            }
            AudioPlaybackService.playAt(this,Uri.parse(r.playableUrl),r.mime,r.title,0,currentUserAgent,cookies==null?"":cookies);
            mediaStatus.setText(t("يعمل الصوت الآن في الخلفية ويمكنك إطفاء الشاشة.","Audio is playing in the background; you can turn the screen off."));
            CookieBridge.delete(cf);
            try{web.evaluateJavascript("document.querySelectorAll('video,audio').forEach(function(m){m.pause();});",null);}catch(Exception ignored){}
        }));
    }

    private void offerDetected(String url,String mime){
        if(!BrowserMediaDetector.isLikelyPlayableMedia(url,mime))return;
        detectedMediaUrl=url==null?"":url;runOnUiThread(this::updateMediaActions);
    }

    private void maybeDetect(String url,String accept){if(BrowserMediaDetector.isLikelyPlayableMedia(url,accept==null?"":accept))offerDetected(url,accept);}

    private void updateMediaActions(){
        if(analyzeAction==null)return;
        String page=currentPageUrl==null?"":currentPageUrl;
        boolean supported=BrowserMediaDetector.isSupportedPage(page)||!detectedMediaUrl.isEmpty();
        analyzeAction.setEnabled(supported);listenAction.setEnabled(supported&&!resolvingBackground);downloadAction.setEnabled(supported);
        mediaStatus.setText(supported?t("تم التعرف على صفحة/وسائط مدعومة — اختر استماع أو تنزيل أو تحليل.","Supported page/media detected — choose Listen, Download or Analyze."):
                t("تصفح الموقع، وستتفعّل أدوات الوسائط عند العثور على مصدر مدعوم.","Browse normally; media actions activate when a supported source is found."));
    }

    private void load(String raw){
        String u=raw==null?"":raw.trim();if(u.isEmpty())return;if(!u.startsWith("http://")&&!u.startsWith("https://"))u="https://"+u;
        detectedMediaUrl="";currentPageUrl=u;address.setText(u);updateMediaActions();web.loadUrl(u);
    }

    private Button btn(String s){
        Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextColor(text);b.setTextSize(12);b.setMinHeight(dp(48));b.setPadding(dp(4),0,dp(4),0);
        android.graphics.drawable.GradientDrawable g=new android.graphics.drawable.GradientDrawable();g.setColor(card);g.setCornerRadius(dp(12));g.setStroke(dp(1),outline);b.setBackground(g);return b;
    }

    private String t(String ar,String en){return arabic?ar:en;}
    private int dp(int x){return (int)(x*getResources().getDisplayMetrics().density+.5f);}

    @Override protected void onPause(){
        if(web!=null)try{web.onPause();}catch(Exception ignored){}
        super.onPause();
    }

    @Override protected void onResume(){
        super.onResume();
        if(web!=null)try{web.onResume();}catch(Exception ignored){}
    }

    @Override protected void onDestroy(){
        resolvingBackground=false;
        if(web!=null){
            try{
                web.stopLoading();
                web.setDownloadListener(null);
                web.setWebChromeClient(null);
                web.setWebViewClient(null);
                web.loadUrl("about:blank");
                web.removeAllViews();
                web.destroy();
            }catch(Exception ignored){}
            web=null;
        }
        super.onDestroy();
    }

    @Override public boolean dispatchTouchEvent(android.view.MotionEvent e){
        PageSwipeNavigator.observe(this,PageSwipeNavigator.BROWSER,e);
        return super.dispatchTouchEvent(e);
    }

    @Override public void onBackPressed(){if(web!=null&&web.canGoBack())web.goBack();else super.onBackPressed();}
}
