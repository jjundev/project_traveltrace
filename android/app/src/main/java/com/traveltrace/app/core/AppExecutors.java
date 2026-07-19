package com.traveltrace.app.core;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * 백그라운드 실행의 단일 진입점. PRD §6: 코루틴 없이 ExecutorService 고정.
 *
 * <p>io 풀 크기는 로컬 I/O(EXIF 읽기·MediaStore 커서·Room) 기준으로 정한다.
 * plan/10 의 "동시 4건"은 rate-limit 하의 <em>네트워크</em> 상한이라 이 값과 무관하다.
 */
@Singleton
public class AppExecutors {

    private static final int MIN_IO_THREADS = 4;

    private final ExecutorService io;
    private final Executor mainThread;

    @Inject
    public AppExecutors() {
        this.io = Executors.newFixedThreadPool(ioPoolSize());
        Handler handler = new Handler(Looper.getMainLooper());
        this.mainThread = handler::post;
    }

    /** 로컬 I/O는 코어 수를 넘겨도 이득이 없고, 4 미만이면 100장 배치가 느려진다. */
    public static int ioPoolSize() {
        return Math.max(MIN_IO_THREADS, Runtime.getRuntime().availableProcessors());
    }

    public ExecutorService io() {
        return io;
    }

    public Executor mainThread() {
        return mainThread;
    }

    public void shutdown() {
        io.shutdownNow();
    }
}
