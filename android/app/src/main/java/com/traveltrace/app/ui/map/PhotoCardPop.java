package com.traveltrace.app.ui.map;

import android.view.View;
import android.view.animation.OvershootInterpolator;

/**
 * 스톱에 도착했을 때 사진 카드가 "톡" 하고 나타나는 연출 (프로토타입 {@code playPop}:
 * scale/opacity 스프링).
 *
 * <p>프로토타입은 CSS transition + 이중 rAF 로 초기 상태를 확정했지만, 여기선 초기 값을
 * <em>동기적으로</em> 찍고 바로 애니메이션을 건다 — 뷰 프로퍼티는 즉시 반영되므로 rAF
 * 트릭이 필요 없고, 그 덕에 시작 상태를 단위 테스트로 확인할 수 있다.
 */
public final class PhotoCardPop {

    public static final long DURATION_MS = 460L;
    public static final float START_SCALE = 0.9f;
    private static final float OVERSHOOT_TENSION = 1.4f;

    private PhotoCardPop() {}

    public static void play(View card) {
        card.animate().cancel();
        card.setAlpha(0f);
        card.setScaleX(START_SCALE);
        card.setScaleY(START_SCALE);
        card.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(DURATION_MS)
                .setInterpolator(new OvershootInterpolator(OVERSHOOT_TENSION))
                .start();
    }
}
