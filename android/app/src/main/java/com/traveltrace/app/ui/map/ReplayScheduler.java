package com.traveltrace.app.ui.map;

/**
 * dwell 타이머 seam. 실제 시간을 흘려보내지 않고 재생 순회를 테스트하기 위한 경계다.
 * 재생 타이밍은 UI 프레임과 붙어 있어 {@code AppExecutors.io()} 가 아니라 메인 스레드에서 돈다.
 */
public interface ReplayScheduler {

    void postDelayed(Runnable task, long delayMs);

    /** 이 스케줄러가 예약한 작업을 전부 취소한다. */
    void cancelAll();
}
