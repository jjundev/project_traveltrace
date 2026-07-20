package com.traveltrace.app.domain;

import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.traveltrace.app.domain.model.PhotoAnalysis;

/**
 * (MediaStore _ID + 콘텐츠 해시) 캐시 (PRD §4.7). 여행과 독립이라 같은 사진은 여행을
 * 넘나들며 hit 된다.
 *
 * <p><b>이 인터페이스만 {@link Callback} 규약을 따르지 않고 동기다.</b> 유일한 호출부인
 * 분석 배치 루프가 이미 {@code AppExecutors.io()} 위에서 사진을 한 장씩 순회하고 있어서,
 * 조회 결과가 있어야 다음 줄(EXIF 를 읽을지 말지)을 정할 수 있기 때문이다. 콜백으로 만들면
 * 그 루프를 콜백 사슬로 뒤집어야 하는데 얻는 게 없다. 대신 두 메서드 모두
 * {@link WorkerThread} 로 못박는다 — 메인스레드에서 부르면 Room 이 던진다.
 */
public interface AnalysisCacheStore {

    /** hit 이면 저장돼 있던 결과를, miss 면 null 을 돌려준다. */
    @Nullable
    @WorkerThread
    PhotoAnalysis get(long mediaStoreId, String contentHash);

    /**
     * 결과를 캐시한다. <b>캐시해도 되는 결과인지는 구현이 판단한다</b> — 호출부는 매번
     * 불러도 되고, 캐시 불가(해시 없음·아직 AI 를 안 돌린 UNKNOWN)면 조용히 무시된다.
     */
    @WorkerThread
    void put(PhotoAnalysis analysis);
}
