package com.traveltrace.app.ui.map;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/**
 * main {@link Looper} 기반 구현. 모든 예약에 같은 토큰을 달아 {@code cancelAll()} 한 번으로
 * 남김없이 지운다 — 화면 이탈 시 타이머가 새는 것을 막는 장치다(S2 수용 기준).
 */
public final class MainThreadReplayScheduler implements ReplayScheduler {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Object token = new Object();

    @Override
    public void postDelayed(Runnable task, long delayMs) {
        handler.postAtTime(task, token, SystemClock.uptimeMillis() + delayMs);
    }

    @Override
    public void cancelAll() {
        handler.removeCallbacksAndMessages(token);
    }
}
