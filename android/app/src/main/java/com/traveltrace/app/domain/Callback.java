package com.traveltrace.app.domain;

/**
 * Repository 비동기 반환 규약. 구현은 결과를 항상 메인스레드에서 전달한다
 * (AppExecutors.mainThread() 경유) — 호출부가 스레드를 신경 쓰지 않게 하기 위함.
 */
public interface Callback<T> {
    void onResult(T value);
}
