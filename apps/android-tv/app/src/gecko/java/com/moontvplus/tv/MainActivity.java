package com.moontvplus.tv;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;

import java.net.URLEncoder;

public class MainActivity extends Activity implements RemoteCommandHandler {
    private static GeckoRuntime runtime;

    private GeckoSession session;
    private GeckoView geckoView;
    private boolean canGoBack = false;
    private LocalRemoteServer localRemoteServer;
    private TextView remoteHintView;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );

        geckoView = new GeckoView(this);
        geckoView.setFocusable(true);
        geckoView.setFocusableInTouchMode(true);
        geckoView.requestFocus();
        setContentView(geckoView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        if (runtime == null) {
            runtime = GeckoRuntime.create(this);
        }

        session = new GeckoSession();
        session.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            @Override
            public void onCanGoBack(GeckoSession session, boolean canGoBackValue) {
                canGoBack = canGoBackValue;
            }
        });
        session.open(runtime);
        geckoView.setSession(session);
        setupLocalRemoteServer();
        session.loadUri(withLocalRemoteHash(buildTvUrl(BuildConfig.BASE_URL)));
    }


    private void setupLocalRemoteServer() {
        localRemoteServer = new LocalRemoteServer(this);
        localRemoteServer.start();
        String url = localRemoteServer.getRemoteUrl();
        if (url == null) return;
        remoteHintView = new TextView(this);
        remoteHintView.setText("局域网遥控：手机浏览器打开\n" + url);
        remoteHintView.setTextColor(0xFFE0E7FF);
        remoteHintView.setTextSize(13);
        remoteHintView.setPadding(18, 12, 18, 12);
        remoteHintView.setBackgroundColor(0xAA111827);
        addContentView(remoteHintView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        mainHandler.postDelayed(() -> {
            if (remoteHintView != null) remoteHintView.setVisibility(View.GONE);
        }, 15000);
    }

    private int keyCodeForRemoteKey(String key, String digit) {
        if ("up".equals(key)) return KeyEvent.KEYCODE_DPAD_UP;
        if ("down".equals(key)) return KeyEvent.KEYCODE_DPAD_DOWN;
        if ("left".equals(key)) return KeyEvent.KEYCODE_DPAD_LEFT;
        if ("right".equals(key)) return KeyEvent.KEYCODE_DPAD_RIGHT;
        if ("ok".equals(key)) return KeyEvent.KEYCODE_DPAD_CENTER;
        if ("back".equals(key)) return KeyEvent.KEYCODE_BACK;
        if ("menu".equals(key)) return KeyEvent.KEYCODE_MENU;
        if ("home".equals(key)) return KeyEvent.KEYCODE_HOME;
        if ("playPause".equals(key)) return KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
        if ("pageUp".equals(key)) return KeyEvent.KEYCODE_PAGE_UP;
        if ("pageDown".equals(key)) return KeyEvent.KEYCODE_PAGE_DOWN;
        if ("digit".equals(key) && digit != null && digit.length() == 1 && digit.charAt(0) >= '0' && digit.charAt(0) <= '9') {
            return KeyEvent.KEYCODE_0 + (digit.charAt(0) - '0');
        }
        return KeyEvent.KEYCODE_UNKNOWN;
    }

    @Override
    public void onRemoteKey(String key, boolean repeat, String digit) {
        int keyCode = keyCodeForRemoteKey(key, digit);
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN) return;
        mainHandler.post(() -> {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                onBackPressed();
                return;
            }
            dispatchKeyCode(keyCode, repeat);
        });
    }

    @Override
    public void onRemoteText(String mode, String text) {
        mainHandler.post(() -> {
            String safeMode = mode == null ? "replace" : mode;
            if ("clear".equals(safeMode) || "replace".equals(safeMode)) {
                dispatchCtrlA();
                dispatchKeyCode(KeyEvent.KEYCODE_DEL, false);
            } else if ("backspace".equals(safeMode)) {
                dispatchKeyCode(KeyEvent.KEYCODE_DEL, false);
                return;
            }

            if (!"clear".equals(safeMode) && text != null && !text.isEmpty()) {
                dispatchText(text);
            }
        });
    }

    private void dispatchKeyCode(int keyCode, boolean repeat) {
        long now = System.currentTimeMillis();
        geckoView.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, repeat ? 1 : 0));
        geckoView.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0));
    }

    private void dispatchCtrlA() {
        long now = System.currentTimeMillis();
        geckoView.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0, KeyEvent.META_CTRL_ON));
        geckoView.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A, 0, KeyEvent.META_CTRL_ON));
    }

    private void dispatchText(String text) {
        KeyEvent[] events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray());
        if (events != null) {
            for (KeyEvent event : events) {
                geckoView.dispatchKeyEvent(event);
            }
            return;
        }

        geckoView.dispatchKeyEvent(new KeyEvent(System.currentTimeMillis(), text, KeyCharacterMap.VIRTUAL_KEYBOARD, 0));
    }

    private String withLocalRemoteHash(String url) {
        String remoteUrl = localRemoteServer == null ? null : localRemoteServer.getRemoteUrl();
        if (remoteUrl == null || remoteUrl.isEmpty()) return url;
        try {
            return url + "#localRemoteUrl=" + URLEncoder.encode(remoteUrl, "UTF-8");
        } catch (Exception ignored) {
            return url;
        }
    }

    private static String buildTvUrl(String baseUrl) {
        String url = baseUrl == null ? "" : baseUrl.trim();
        if (url.isEmpty()) {
            url = "http://192.168.1.10:3000";
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://" + url;
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (url.endsWith("/tv")) {
            return url;
        }
        return url + "/tv";
    }

    @Override
    public void onBackPressed() {
        if (session != null && canGoBack) {
            session.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (localRemoteServer != null) {
            localRemoteServer.stop();
            localRemoteServer = null;
        }
        if (session != null) {
            session.close();
            session = null;
        }
        super.onDestroy();
    }
}
