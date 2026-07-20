package com.traveltrace.app.ui.map;

import java.util.ArrayList;
import java.util.List;

/**
 * dwell 타이머를 테스트가 손으로 굴리게 해 주는 더블. 진짜 시간을 흘려보내지 않으므로
 * 재생 순회 테스트가 빠르고 결정적이다.
 */
public class FakeReplayScheduler implements ReplayScheduler {

    private final List<Runnable> pending = new ArrayList<>();
    private long lastDelayMs = -1L;
    public int cancelCount;

    @Override
    public void postDelayed(Runnable task, long delayMs) {
        lastDelayMs = delayMs;
        pending.add(task);
    }

    @Override
    public void cancelAll() {
        cancelCount++;
        pending.clear();
    }

    public int pendingCount() {
        return pending.size();
    }

    public long lastDelayMs() {
        return lastDelayMs;
    }

    /** 예약된 작업을 전부 실행한다. 실행 중 새로 예약된 건 다음 호출로 미룬다. */
    public void runPending() {
        List<Runnable> due = new ArrayList<>(pending);
        pending.clear();
        for (Runnable r : due) {
            r.run();
        }
    }
}
