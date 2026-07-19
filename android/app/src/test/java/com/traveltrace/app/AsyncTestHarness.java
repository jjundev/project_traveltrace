package com.traveltrace.app;

import static org.junit.Assert.assertTrue;

import android.os.Looper;

import androidx.lifecycle.LiveData;

import com.traveltrace.app.domain.Callback;

import org.robolectric.shadows.ShadowLooper;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 비동기 결과(리포지토리 {@link Callback} 콜백, ViewModel 의 {@link LiveData})를 기다리는
 * 테스트 전용 폴링 헬퍼.
 *
 * <p>이 프로젝트의 리포지토리/ViewModel 은 실제 작업을 {@code AppExecutors.io()} (진짜 백그라운드
 * {@link java.util.concurrent.ExecutorService})에서 실행하고, 결과를 {@code
 * AppExecutors.mainThread()} 경유로 메인 루퍼에 포스팅한다. Robolectric 의 메인 루퍼는 저절로
 * 펌프되지 않으므로 테스트가 직접 {@link ShadowLooper#idleMainLooper()} 를 불러 밀어내야 하는데,
 * 트리거 직후 <b>단 한 번만</b> 호출하면 io 스레드풀과 경쟁하게 된다 — 그 시점엔 백그라운드 작업이
 * 아직 안 끝나 메인 루퍼에 아무것도 쌓여 있지 않을 수 있다(관찰상 in-memory Room 삽입도 수십 ms
 * 걸린다). 그래서 두 메서드 모두 "루퍼를 비우고, 아직이면 짧게 자고, 데드라인까지 반복"하는
 * 폴링 루프를 쓴다. 이 루프를 "단순화"해서 단발 idle 호출로 되돌리면 타이밍에 따라 간헐적으로만
 * 실패하는 테스트가 된다 — 그러지 말 것.
 *
 * <p>{@code RoomTripRepositoryTest}, {@code HomeViewModelTest} 에서 각각 독립적으로 작성됐던
 * 동일한 폴링 로직을 여기로 모았다. 새로운 비동기 로딩을 기다리는 테스트가 생기면(Callback 이든
 * LiveData 든) 직접 루프를 다시 쓰지 말고 이 클래스를 확장할 것.
 */
public final class AsyncTestHarness {

    private static final long DEFAULT_TIMEOUT_MS = 5_000L;
    private static final long POLL_INTERVAL_MS = 5L;

    private AsyncTestHarness() {
    }

    /**
     * {@link Callback}-스타일 비동기 호출의 결과를 기다린다.
     *
     * <p>{@code com.traveltrace.app.domain.Callback} 의 계약(자바독 참고)은 "구현은 결과를 항상
     * 메인스레드에서 전달한다"이다 — 호출부가 스레드를 신경 쓰지 않게 하기 위한 규약이므로, 이
     * 헬퍼는 그 계약을 <b>무조건</b> 검증한다(옵트인으로 만들지 않는다). 예전에 이 폴링 로직이
     * 파일마다 따로 복사됐을 때 한 사본이 바로 이 검증을 빠뜨렸었다 — 옵트인이었다면 그 드리프트를
     * 막지 못했을 것이다. 계약을 지키는 구현이라면 이 assert 는 항상 통과하므로, 무조건 검증해도
     * 기존에 통과하던 테스트를 깨뜨리지 않는다.
     */
    public static <T> T awaitCallback(Consumer<Callback<T>> call) {
        AtomicReference<T> box = new AtomicReference<>();
        AtomicBoolean done = new AtomicBoolean(false);
        AtomicBoolean onMainLooper = new AtomicBoolean(false);
        call.accept(v -> {
            box.set(v);
            onMainLooper.set(Looper.myLooper() == Looper.getMainLooper());
            done.set(true);
        });

        pollUntil(done::get);

        assertTrue("콜백이 5초 안에 와야 한다", done.get());
        assertTrue("콜백은 메인 루퍼에서 전달되어야 한다", onMainLooper.get());
        return box.get();
    }

    /**
     * {@code trigger} 를 실행한 뒤, {@code liveData} 의 값이 {@code condition} 을 만족할 때까지
     * 기다린다. {@code description} 은 타임아웃 실패 메시지에 그대로 들어가므로 "무엇을 기다렸는지"
     * 사람이 읽을 수 있게 적는다(예: "HomeViewModel.refresh()").
     *
     * <p>기다리는 대상은 "조건을 만족하는 값"이지 "콜백 도착" 이 아니다 — LiveData 는 콜백처럼
     * 한 번 도착하고 끝나는 게 아니라 이미 값을 들고 있을 수도 있으므로, {@code condition} 을
     * 호출부가 명시해서 "이전 값과 다른 인스턴스" 든 "특정 필드가 특정 값" 이든 자유롭게 표현하게
     * 한다.
     */
    public static <T> T awaitLiveData(
            LiveData<T> liveData, Runnable trigger, Predicate<T> condition, String description) {
        trigger.run();

        AtomicReference<T> current = new AtomicReference<>(liveData.getValue());
        pollUntil(() -> {
            current.set(liveData.getValue());
            return condition.test(current.get());
        });

        assertTrue(description + " 결과가 5초 안에 조건을 만족해야 한다", condition.test(current.get()));
        return current.get();
    }

    /**
     * "루퍼를 비우고, 조건이 아직 안 됐으면 짧게 자고, 데드라인까지 반복"하는 공통 루프.
     * io() 스레드풀과의 경쟁 때문에 단발 {@link ShadowLooper#idleMainLooper()} 호출로는
     * 안 되는 이유는 클래스 자바독 참고.
     */
    private static void pollUntil(BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + DEFAULT_TIMEOUT_MS;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper();
            if (!condition.getAsBoolean()) {
                try {
                    Thread.sleep(POLL_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            }
        }
    }
}
