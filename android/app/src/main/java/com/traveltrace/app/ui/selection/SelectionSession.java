package com.traveltrace.app.ui.selection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * SELECT → ANALYZE 로 넘기는 선택 사진 목록. 최대 100개의 long 이라 인메모리로 충분하고,
 * plan/05 의 "대용량도 Bundle 직렬화 없이" 요구를 만족한다.
 *
 * <p>프로세스 사망 시에는 비어 있게 된다 — ANALYZE 진입에서 그 상태를 감지해 Home 으로
 * 돌려보낸다(Task 9). 식별자 하나뿐인 tripId 는 여기가 아니라 nav argument 로 나른다.
 */
@Singleton
public class SelectionSession {

    private final List<Long> ids = new ArrayList<>();

    @Inject
    public SelectionSession() {}

    public synchronized void put(List<Long> next) {
        ids.clear();
        ids.addAll(next);
    }

    /** 방어적 복사본. 호출부가 바꿔도 세션은 그대로다. */
    public synchronized List<Long> ids() {
        return new ArrayList<>(ids);
    }

    public synchronized boolean isEmpty() {
        return ids.isEmpty();
    }

    public synchronized void clear() {
        ids.clear();
    }

    /** 테스트·디버그용 읽기 전용 뷰. */
    public synchronized List<Long> readOnly() {
        return Collections.unmodifiableList(new ArrayList<>(ids));
    }
}
