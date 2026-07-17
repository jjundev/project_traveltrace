package com.traveltrace.app.ui.common;

import android.os.Handler;
import android.os.Looper;
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

    // View.postDelayed/removeCallbacks는 뷰가 attach된 동안에만 실제 윈도우 Handler에 닿는다.
    // removeCallbacks는 호출 시점에 attach 여부를 확인하는데, 이미 detach된 뷰라면 attach되지
    // 않은 run queue만 비울 뿐 실제 Handler에 걸린 메시지는 지우지 못한다. AndroidX Fragment는
    // onDestroyView() 이전에 뷰를 컨테이너에서 detach하므로, 그 시점에 View 기반으로 취소하면
    // 예약된 숨김이 파괴된 뷰를 향해 그대로 발화할 수 있다. 그래서 attach 상태와 무관하게
    // 취소가 항상 먹히도록 main Looper에 바인딩된 별도 Handler에 예약/취소한다.
    private static final Handler HANDLER = new Handler(Looper.getMainLooper());

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
        HANDLER.postDelayed(hide, DURATION_MS);
    }

    /**
     * 화면이 사라질 때 예약된 숨김을 취소한다 — 파괴된 뷰로 콜백이 튀지 않게.
     * main Looper Handler에 취소를 위임하므로 뷰의 attach 상태와 무관하게 항상 취소된다.
     */
    public static void cancel(View anchorRoot) {
        TextView pill = anchorRoot.findViewById(R.id.toastPill);
        if (pill == null) return;
        Object pending = pill.getTag(R.id.toastPill);
        if (pending instanceof Runnable) {
            HANDLER.removeCallbacks((Runnable) pending);
            pill.setTag(R.id.toastPill, null);
        }
    }
}
