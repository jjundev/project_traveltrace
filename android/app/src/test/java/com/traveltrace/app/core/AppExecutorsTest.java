package com.traveltrace.app.core;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowLooper;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(RobolectricTestRunner.class)
public class AppExecutorsTest {

    @Test
    public void ioRunsOffTheMainThread() throws Exception {
        AppExecutors executors = new AppExecutors();
        AtomicReference<Thread> ran = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        executors.io().execute(() -> {
            ran.set(Thread.currentThread());
            done.countDown();
        });

        assertTrue("io 작업이 2초 안에 끝나야 한다", done.await(2, TimeUnit.SECONDS));
        assertNotEquals("io 는 메인스레드에서 돌면 안 된다",
                Thread.currentThread(), ran.get());
        executors.shutdown();
    }

    @Test
    public void mainThreadPostsToTheMainLooper() {
        AppExecutors executors = new AppExecutors();
        AtomicReference<Boolean> ranOnMain = new AtomicReference<>(null);

        executors.mainThread().execute(() ->
                ranOnMain.set(android.os.Looper.myLooper() == android.os.Looper.getMainLooper()));

        // Robolectric 은 메인 루퍼를 자동으로 돌리지 않는다 — 명시적으로 비운다.
        ShadowLooper.idleMainLooper();

        assertTrue("mainThread 는 메인 루퍼에서 실행되어야 한다", Boolean.TRUE.equals(ranOnMain.get()));
        executors.shutdown();
    }

    @Test
    public void ioPoolIsAtLeastFourThreads() {
        assertTrue("동시 EXIF 읽기를 위해 최소 4스레드", AppExecutors.ioPoolSize() >= 4);
    }
}
