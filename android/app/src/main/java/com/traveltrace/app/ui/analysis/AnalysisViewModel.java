package com.traveltrace.app.ui.analysis;

import android.content.Context;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.traveltrace.app.R;
import com.traveltrace.app.analysis.PhotoAnalysisPipeline;
import com.traveltrace.app.core.AnalysisCostLog;
import com.traveltrace.app.core.AppExecutors;
import com.traveltrace.app.core.model.LocationClassification;
import com.traveltrace.app.data.media.ContentHasher;
import com.traveltrace.app.data.media.GalleryImage;
import com.traveltrace.app.data.media.MediaStoreImageSource;
import com.traveltrace.app.domain.AnalysisCacheStore;
import com.traveltrace.app.domain.PhotoAnalysisRepository;
import com.traveltrace.app.domain.model.PhotoAnalysis;
import com.traveltrace.app.ui.selection.SelectionSession;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;
import dagger.hilt.android.qualifiers.ApplicationContext;

/**
 * ANALYZE 진행. 선택된 사진을 EXIF 로 훑어 진행률을 올리고, 끝나면 여행으로 저장한다.
 *
 * <p>사진 1장의 분석은 {@link PhotoAnalysisPipeline} 이 통째로 소유한다 — 이 ViewModel 은
 * GPS 냐 AI 냐를 알지 못하며, 알 필요도 없다. S3 는 <em>직렬</em> 처리다(동시 4건·재시도·
 * 비용 상한은 S4 가 이 루프를 대체하며 붙인다).
 *
 * <p>진짜 취소 시점은 {@link #onCleared()} 다 — 회전으로 View 만 재생성되는 경우
 * {@code onDestroyView()} 는 매번 불리지만 이 ViewModel 은 살아남으므로 잘못 취소되면
 * 안 된다. {@code onCleared()} 는 ViewModelStore 가 진짜로 버려질 때(뒤로가기로 화면을
 * 이탈하거나, 리컴포지션이 아닌 프래그먼트 폐기)만 호출되므로 "회전"과 "이탈"을 정확히
 * 구분하는 신호다. {@link #cancel()} 은 뒤로가기 버튼처럼 사용자의 명시적 이탈 의도가
 * 이미 확실한 자리에서 조금 더 빨리 멈추기 위한 보조 훅으로 남긴다. 취소 플래그를 매
 * 사진마다 확인하므로 진행 중이던 배치가 이후 사진의 EXIF 는 더 읽지 않고 끝난다 —
 * 다만 저장 커밋 자체는 별도의 수용된 레이스가 있다({@link #run} 참고).
 */
@HiltViewModel
public class AnalysisViewModel extends ViewModel {

    private final Context context;
    private final MediaStoreImageSource imageSource;
    private final PhotoAnalysisPipeline pipeline;
    private final PhotoAnalysisRepository analysisRepository;
    private final SelectionSession session;
    private final AppExecutors executors;
    private final ContentHasher hasher;
    private final AnalysisCacheStore cacheStore;
    private final AnalysisCostLog costLog;

    private final MutableLiveData<AnalysisUiState> state = new MutableLiveData<>();
    private final MutableLiveData<String> savedTripId = new MutableLiveData<>();
    private final MutableLiveData<Boolean> abandoned = new MutableLiveData<>(false);
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicBoolean started = new AtomicBoolean(false);

    @Inject
    public AnalysisViewModel(@ApplicationContext Context context,
                             MediaStoreImageSource imageSource,
                             PhotoAnalysisPipeline pipeline,
                             PhotoAnalysisRepository analysisRepository,
                             SelectionSession session,
                             AppExecutors executors,
                             ContentHasher hasher,
                             AnalysisCacheStore cacheStore,
                             AnalysisCostLog costLog) {
        this.context = context;
        this.imageSource = imageSource;
        this.pipeline = pipeline;
        this.analysisRepository = analysisRepository;
        this.session = session;
        this.executors = executors;
        this.hasher = hasher;
        this.cacheStore = cacheStore;
        this.costLog = costLog;
    }

    public LiveData<AnalysisUiState> state() {
        return state;
    }

    /** 저장이 끝나면 새 tripId 가 실린다 — Fragment 가 이걸 들고 MAP 으로 간다. */
    public LiveData<String> savedTripId() {
        return savedTripId;
    }

    /** 선택 세션이 비어 분석할 게 없는 상태(프로세스 사망 후 재진입). */
    public LiveData<Boolean> abandoned() {
        return abandoned;
    }

    /** 중복 실행을 막으며 1회만 시작한다(회전 시 재호출되어도 안전). */
    public void start() {
        if (!started.compareAndSet(false, true)) return;

        List<Long> selectedIds = session.ids();
        if (selectedIds.isEmpty()) {
            abandoned.setValue(true);
            return;
        }

        pipeline.resetFailureCount();
        costLog.reset();
        state.setValue(new AnalysisUiState(0, selectedIds.size(), false, "", 0, 0));

        // 선택은 이미 SELECT 에서 확정됐다 — 갤러리 전체(수만 장일 수 있다)를 훑어 그중
        // 골라내는 대신, 처음부터 선택된 id 만 걸러 쿼리한다(finding 5). 필터링 자체도
        // MediaStoreImageSource.loadByIds() 가 io() 스레드에서 SQL 로 하므로, 메인 스레드는
        // 결과를 그대로 받기만 한다.
        imageSource.loadByIds(selectedIds, targets -> {
            if (targets.isEmpty()) {
                abandoned.setValue(true);
                return;
            }
            executors.io().execute(() -> run(targets));
        });
    }

    /**
     * 명시적 이탈(뒤로가기 버튼 등) 시 호출해 조금 더 빨리 멈춘다. 진짜 취소 보장은
     * {@link #onCleared()} 가 맡으므로, 이 호출이 없어도(예: 시스템 뒤로가기 제스처)
     * ViewModel 이 실제로 폐기되는 순간 동일하게 취소된다.
     */
    public void cancel() {
        cancelled.set(true);
    }

    /**
     * ViewModelStore 가 진짜로 버려질 때만 불린다 — 회전 등 View 재생성으로는 호출되지
     * 않는다(finding 1). 그래서 "취소"의 유일한 신뢰 가능한 신호를 여기 둔다.
     */
    @Override
    protected void onCleared() {
        cancelled.set(true);
    }

    private void run(List<GalleryImage> targets) {
        List<PhotoAnalysis> results = new ArrayList<>();
        int total = targets.size();

        for (int i = 0; i < total; i++) {
            if (cancelled.get()) return;

            GalleryImage image = targets.get(i);
            PhotoAnalysis analysis = analyze(image);
            results.add(analysis);

            int analyzed = i + 1;
            int placed = countPlaced(results);
            int unknown = countUnknown(results);
            // AI 가 이름을 알아냈으면 그걸 보여준다 — "현재 인식 텍스트"라는 이름값을
            // 이제야 한다. 못 알아냈으면 S1 처럼 파일명으로 폴백한다.
            String currentName = progressLabel(analysis, image);
            executors.mainThread().execute(() -> state.setValue(
                    new AnalysisUiState(analyzed, total, false, currentName, placed, unknown)));
        }

        if (cancelled.get()) return;

        String name = tripName(context, results);
        String zoneId = TimeZone.getDefault().getID();
        analysisRepository.saveTrip(name, zoneId, results, tripId -> {
            // 레포지토리는 Room 트랜잭션을 커밋한 "뒤에" 이 콜백을 메인 스레드로 올린다.
            // 즉 이 콜백이 실행된 시점엔 trip 행이 이미 durable 하게 저장되어 있다 —
            // cancelled 가 true 여도 그 사이 취소가 끼어든 것뿐, 쓰기 자체를 되돌릴
            // 방법은 없다(finding 2, 레포지토리는 out of bounds 라 원자적으로 만들지
            // 않기로 합의된 수용된 레이스). 그래서 세션은 취소 여부와 무관하게 "항상"
            // 비운다 — 안 비우면 같은 선택으로 재진입한 다음 AnalysisViewModel 이 배치를
            // 통째로 다시 돌려 같은 사진들로 두 번째 "유령 여행"을 만들 수 있고, 그 이중
            // 저장이 이 픽스가 실제로 막으려는 결과다. 반면 이 화면 자체는 이미 사라진
            // 뒤일 수 있으니 state/savedTripId 같은 UI 갱신은 취소 시 계속 억제한다.
            costLog.logSummary("analyze " + total + " photos");
            session.clear();
            if (cancelled.get()) return;
            state.setValue(new AnalysisUiState(
                    total, total, true, name, countPlaced(results), countUnknown(results)));
            savedTripId.setValue(tripId);
        });
    }

    /**
     * 사진 1장을 해석한다: 해시 → 캐시 조회 → miss 일 때만 실제 판독.
     *
     * <p>순서가 중요하다. 해시를 앞 64KiB 로만 계산하기 때문에(plan/04 결정) 캐시 조회를
     * 판독 <em>앞에</em> 둘 수 있고, 그래야 hit 이 실제로 일을 줄인다. hit 이면
     * {@link PhotoAnalysisPipeline}(다운스케일·업로드·AI·지오코딩)이 통째로 스킵된다 —
     * 그때 절약되는 것은 로컬 파싱이 아니라 돈이다.
     *
     * <p>해시를 못 구했으면(사진이 지워졌거나 권한이 빠졌다) 캐시를 통째로 건너뛰고 평소대로
     * 판독한다 — 캐시는 최적화지 정확성의 전제가 아니다.
     */
    private PhotoAnalysis analyze(GalleryImage image) {
        String hash = hasher.hash(image.contentUri, image.sizeBytes);

        if (hash != null) {
            // GalleryImage 쪽 필드명은 id, PhotoAnalysis 쪽은 mediaStoreId 다 — 같은 값이다.
            PhotoAnalysis cached = cacheStore.get(image.id, hash);
            if (cached != null) {
                costLog.recordCacheHit();
                // 파일명은 콘텐츠가 아니라 MediaStore 행의 속성이라 캐시가 들고 있지 않다 —
                // 살아 있는 커서 값으로 채운다(사용자가 이름을 바꿨을 수 있다).
                cached.displayName = image.displayName;
                return cached;
            }
            // 조회할 해시가 있었는데도 못 찾았을 때만 진짜 miss 다 — 해시 자체가 없던
            // 아래 분기는 캐시를 애초에 못 건드린 "skip"이라 miss 로 세면 신호가 흐려진다.
            costLog.recordCacheMiss();
        }

        // 캐시 miss — S3 파이프라인이 EXIF 부터 (GPS 없으면) 다운스케일·AI·지오코딩까지
        // 통째로 돈다. 여기가 유료 호출이 실제로 나가는 유일한 자리다.
        PhotoAnalysis fresh = pipeline.analyze(image);
        // 캐시 키는 hasher 의 64KiB 해시로 통일한다 — 파이프라인이 AI 경로에서 내부적으로
        // 채운 SHA-256 을 여기서 캐시 키 해시로 덮어써 put/get 이 같은 값을 쓰게 한다.
        fresh.contentHash = hash;
        // put() 이 캐시 가능 여부를 스스로 판단한다 — 여기서 거르지 않는다.
        cacheStore.put(fresh);
        return fresh;
    }

    private static int countPlaced(List<PhotoAnalysis> results) {
        int n = 0;
        for (PhotoAnalysis a : results) {
            if (a.classification == LocationClassification.PLACED) n++;
        }
        return n;
    }

    /**
     * NAME_ONLY 를 위치 미상에 합산한다. 사용자에겐 둘 다 "지도에 없는 사진"이고,
     * 이 둘을 화면에서 갈라 보여주는 건 S6 다 — 그때 이 합산을 쪼갠다.
     */
    private static int countUnknown(List<PhotoAnalysis> results) {
        int n = 0;
        for (PhotoAnalysis a : results) {
            if (a.classification == LocationClassification.UNKNOWN
                    || a.classification == LocationClassification.NAME_ONLY) {
                n++;
            }
        }
        return n;
    }

    /** 인식된 장소명 > 도시명 > 파일명. 전부 저장된 값이라 새 문자열 리소스가 필요 없다. */
    private static String progressLabel(PhotoAnalysis analysis, GalleryImage image) {
        if (analysis.landmarkName != null && !analysis.landmarkName.trim().isEmpty()) {
            return analysis.landmarkName;
        }
        if (analysis.city != null && !analysis.city.trim().isEmpty()) {
            return analysis.city;
        }
        return image.displayName;
    }

    /** 원본 접근 실패 수 — 완료 안내에 덧붙인다(조용한 GPS 누락 감지). */
    public int originalAccessFailures() {
        return pipeline.originalAccessFailures();
    }

    /**
     * "2024년 6월 여행". 도시명은 역지오코딩이 필요해 S1 범위 밖이라 넣지 않는다
     * (plan/15 의 "YYYY 도시 여행" 제안에서 의도적으로 이탈).
     *
     * <p>저장되는 값이지만 그대로 화면에 뜨므로 문자열 리소스로 조립한다.
     */
    private static String tripName(Context context, List<PhotoAnalysis> results) {
        Long earliest = null;
        for (PhotoAnalysis a : results) {
            if (a.takenAtUtc == null) continue;
            if (earliest == null || a.takenAtUtc < earliest) earliest = a.takenAtUtc;
        }
        Calendar cal = Calendar.getInstance();
        if (earliest != null) cal.setTimeInMillis(earliest);
        return context.getString(R.string.analyze_trip_name,
                cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1);
    }
}
