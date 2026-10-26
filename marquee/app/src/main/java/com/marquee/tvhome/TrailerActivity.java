package com.marquee.tvhome;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;

/**
 * A trailer in a window over Marquee, with YouTube's embedded player. Back closes it and leaves you where you were.
 * Runs in its own process (":trailer"), so the web player's memory goes when the window closes. A video whose owner
 * blocks embedding opens in the YouTube app instead.
 *
 * Remote: OK or Play/Pause pauses and resumes, Left and Right skip 10 seconds, Back closes.
 */
public final class TrailerActivity extends Activity {
    private static final String EXTRA_KEY = "youtube_key";
    private WebView web;
    private String key;

    static void open(Context context, String youtubeKey) {
        context.startActivity(new Intent(context, TrailerActivity.class).putExtra(EXTRA_KEY, youtubeKey));
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        key = getIntent().getStringExtra(EXTRA_KEY);
        if (key == null || !key.matches("[A-Za-z0-9_-]{6,20}")) {
            finish();
            return;
        }
        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        web.setFocusable(false);
        web.setFocusableInTouchMode(false);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new Bridge(), "Marquee");
        web.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        setContentView(web);
        // After setContentView: a floating window is reset to wrap_content there, which a WebView reads as zero.
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int w = Math.round(dm.widthPixels * 0.78f);
        getWindow().setLayout(w, w * 9 / 16);
        // An https page of our own as the base, so YouTube receives an origin and referrer for the embed.
        web.loadDataWithBaseURL("https://marquee.tvhome/", html(key), "text/html", "utf-8", null);
    }

    private static String html(String key) {
        return "<!doctype html><html><head><meta name=viewport content='width=device-width,initial-scale=1'>"
                + "<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}#p{width:100%;height:100%}</style>"
                + "</head><body><div id=p></div><script>"
                + "var p;function onYouTubeIframeAPIReady(){p=new YT.Player('p',{videoId:'" + key + "',host:'https://www.youtube-nocookie.com',"
                + "playerVars:{autoplay:1,controls:0,rel:0,fs:0,iv_load_policy:3,playsinline:1,origin:'https://marquee.tvhome'},"
                + "events:{onReady:function(e){e.target.playVideo();},"
                + "onError:function(e){Marquee.failed(e.data);},"
                + "onStateChange:function(e){if(e.data===0)Marquee.ended();}}});}"
                + "function toggle(){if(!p)return;if(p.getPlayerState()===1)p.pauseVideo();else p.playVideo();}"
                + "function seek(d){if(p)p.seekTo(Math.max(0,p.getCurrentTime()+d),true);}"
                + "</script><script src='https://www.youtube.com/iframe_api'></script></body></html>";
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_HOME || k == KeyEvent.KEYCODE_VOLUME_UP
                || k == KeyEvent.KEYCODE_VOLUME_DOWN || k == KeyEvent.KEYCODE_VOLUME_MUTE) {
            return super.dispatchKeyEvent(e);
        }
        if (e.getAction() != KeyEvent.ACTION_DOWN || web == null) return true;
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                web.evaluateJavascript("toggle()", null);
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                web.evaluateJavascript("seek(-10)", null);
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                web.evaluateJavascript("seek(10)", null);
                break;
            default:
                break;
        }
        return true;
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Leaving the window (Home, an input switch) ends the trailer rather than playing on unseen.
        if (!isFinishing()) finish();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.loadUrl("about:blank");
            web.destroy();
            web = null;
        }
        super.onDestroy();
        // This window has the ":trailer" process to itself: end it so the web player's memory is freed at once.
        if (isFinishing()) android.os.Process.killProcess(android.os.Process.myPid());
    }

    private final class Bridge {
        /** 101/150/152/153: the owner allows no embedding; play it in the YouTube app instead. */
        @JavascriptInterface
        public void failed(int code) {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=" + key))
                        .setPackage(Services.YOUTUBE.pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (i.resolveActivity(getPackageManager()) == null) i.setPackage(null);
                Actions.start(TrailerActivity.this, i);
                finish();
            });
        }

        @JavascriptInterface
        public void ended() {
            runOnUiThread(TrailerActivity.this::finish);
        }
    }
}
