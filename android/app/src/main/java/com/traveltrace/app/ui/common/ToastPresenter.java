package com.traveltrace.app.ui.common;

import android.view.View;
import android.widget.TextView;

import com.traveltrace.app.R;

/**
 * 프로토타입의 토스트 pill(하단 고정, 1.9초 후 사라짐). 시스템 Toast/Snackbar 대신
 * 화면 안 pill 로 렌더하므로 프로토타입과 위치·모양이 일치한다.
 * 토스트 pill(@id/toastPill)을 가진 레이아웃의 루트를 넘긴다.
 */
public final class ToastPresenter {

    private static final long DURATION_MS = 1900L;

    private ToastPresenter() {}

    public static void show(View anchorRoot, String message) {
        TextView pill = anchorRoot.findViewById(R.id.toastPill);
        if (pill == null) return;

        // 연속 토스트가 겹치면 앞선 숨김 예약이 새 토스트를 조기에 지운다 → 예약을 갈아끼운다.
        cancel(anchorRoot);

        pill.setText(message);
        pill.setVisibility(View.VISIBLE);

        Runnable hide = () -> pill.setVisibility(View.GONE);
        pill.setTag(R.id.toastPill, hide);
        pill.postDelayed(hide, DURATION_MS);
    }

    /** 화면이 사라질 때 예약된 숨김을 취소한다 — 파괴된 뷰로 콜백이 튀지 않게. */
    public static void cancel(View anchorRoot) {
        TextView pill = anchorRoot.findViewById(R.id.toastPill);
        if (pill == null) return;
        Object pending = pill.getTag(R.id.toastPill);
        if (pending instanceof Runnable) {
            pill.removeCallbacks((Runnable) pending);
            pill.setTag(R.id.toastPill, null);
        }
    }
}
