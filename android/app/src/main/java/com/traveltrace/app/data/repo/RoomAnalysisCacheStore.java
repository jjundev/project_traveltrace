package com.traveltrace.app.data.repo;

import androidx.annotation.Nullable;

import com.traveltrace.app.core.model.LocationSource;
import com.traveltrace.app.data.db.AnalysisCacheEntity;
import com.traveltrace.app.data.db.TravelTraceDatabase;
import com.traveltrace.app.domain.AnalysisCacheStore;
import com.traveltrace.app.domain.model.PhotoAnalysis;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * 호출부가 이미 io 스레드에 있으므로 AppExecutors 를 거치지 않고 곧장 Room 을 친다
 * (이유는 {@link AnalysisCacheStore} 자바독).
 */
@Singleton
public class RoomAnalysisCacheStore implements AnalysisCacheStore {

    private final TravelTraceDatabase db;

    @Inject
    public RoomAnalysisCacheStore(TravelTraceDatabase db) {
        this.db = db;
    }

    @Nullable
    @Override
    public PhotoAnalysis get(long mediaStoreId, String contentHash) {
        if (contentHash == null) return null;
        AnalysisCacheEntity entry = db.analysisCacheDao().find(mediaStoreId, contentHash);
        if (entry == null) return null;

        PhotoAnalysis a = new PhotoAnalysis();
        a.mediaStoreId = entry.mediaStoreId;
        a.contentHash = entry.contentHash;
        a.takenAtUtc = entry.takenAtUtc;
        a.takenAtHasOffset = entry.takenAtHasOffset;
        a.lat = entry.lat;
        a.lng = entry.lng;
        a.source = entry.source;
        a.classification = entry.classification;
        // displayName 은 일부러 채우지 않는다 — 파일명은 콘텐츠가 아니라 MediaStore 행의
        // 속성이라 사용자가 이름을 바꾸면 캐시된 값이 낡는다. 호출부가 살아 있는 커서
        // 값으로 덮는다.
        return a;
    }

    @Override
    public void put(PhotoAnalysis analysis) {
        if (analysis.contentHash == null) return;
        if (!isCacheable(analysis)) return;

        // isCacheable() 이 걸러낸 대로 여기 오는 건 항상 GPS 결과라, 지금은 GPS/EXIF 가
        // 채우는 필드만 저장한다 — landmarkName/city/country/confidence/model 은 일부러
        // 옮기지 않는다(엔티티엔 컬럼이 있어도 S1/S8 결과에선 항상 null). S3(AI 지명)·
        // S5(모델 id) 가 이 값들을 채우기 시작하면 여기와 get() 양쪽에 왕복시키는 코드를
        // 추가해야 한다 — 지금 침묵하고 있다고 잊지 말 것.
        AnalysisCacheEntity entry = new AnalysisCacheEntity();
        entry.mediaStoreId = analysis.mediaStoreId;
        entry.contentHash = analysis.contentHash;
        entry.lat = analysis.lat;
        entry.lng = analysis.lng;
        entry.source = analysis.source;
        entry.classification = analysis.classification;
        entry.takenAtUtc = analysis.takenAtUtc;
        entry.takenAtHasOffset = analysis.takenAtHasOffset;
        entry.createdAt = System.currentTimeMillis();
        db.analysisCacheDao().upsert(entry);
    }

    /**
     * GPS 로 확정된 결과만 캐시한다.
     *
     * <p>{@code UNKNOWN}(=GPS 없음)은 "위치가 없는 사진"이 아니라 <b>"아직 AI 를 안 돌린
     * 사진"</b>이다 — S1/S8 에는 AI 경로가 없어서 그렇게 끝났을 뿐이다. 이걸 캐시하면 S3 이
     * 붙었을 때 GPS 없는 사진이 전부 캐시 hit 으로 처리돼 영원히 AI 로 가지 못한다. 그래서
     * 이 가드는 호출부가 아니라 여기 있다 — 다음 슬라이스가 잊어버릴 수 없게.
     *
     * <p>S3 이 AI 결과를 캐시하기 시작하면 이 조건에 {@code source == AI} 를 더한다.
     */
    private static boolean isCacheable(PhotoAnalysis analysis) {
        return analysis.source == LocationSource.GPS;
    }
}
