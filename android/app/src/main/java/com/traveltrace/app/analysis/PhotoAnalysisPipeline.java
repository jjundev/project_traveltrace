package com.traveltrace.app.analysis;

import android.util.Log;

import com.traveltrace.app.core.model.LocationSource;
import com.traveltrace.app.core.model.RecognitionResult;
import com.traveltrace.app.data.exif.ExifExtractor;
import com.traveltrace.app.data.media.GalleryImage;
import com.traveltrace.app.domain.VisionProvider;
import com.traveltrace.app.domain.model.PhotoAnalysis;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * 사진 1장 → {@link PhotoAnalysis}. <b>전송 파이프라인의 유일한 진입점</b>(PRD §5).
 *
 * <p>순서가 이 클래스 안에 갇혀 있다는 것이 요점이다:
 * <ol>
 *   <li><b>①</b> 원본에서 GPS·촬영 시각 EXIF 를 <em>로컬에서</em> 읽는다.</li>
 *   <li><b>②</b> GPS 가 없을 때만 — 다운스케일 + JPEG 재인코딩(EXIF 제거 보장) + 해시.</li>
 *   <li><b>③</b> 그 산출물만 업로드한다.</li>
 * </ol>
 * 호출부는 {@link #analyze} 하나만 볼 수 있으므로 "EXIF 를 읽기 전에 원본을 올린다"거나
 * "원본을 그대로 올린다"는 코드를 <em>쓸 수가 없다</em>. plan/07 이 요구한 캡슐화다.
 *
 * <p><b>GPS 사진은 ② 이후를 통째로 건너뛴다</b> — 비용(업로드·추론)과 프라이버시(전송)
 * 양쪽에서 이득이고, PRD §4.2 의 우선순위 규칙 그 자체다.
 *
 * <p><b>S3 는 직렬·단일 프로바이더다.</b> 동시 4건·타임아웃·재시도·429 백오프·여행당
 * 비용 상한·(_ID+해시) 캐시는 전부 S4 소관이며, 이 클래스를 감싸는 방식으로 붙는다.
 * OpenAI 폴백은 S5 다. 여기에 미리 넣지 않는다.
 */
@Singleton
public class PhotoAnalysisPipeline {

    private static final String TAG = "PhotoAnalysisPipeline";

    private final ExifExtractor exif;
    private final UploadPreparer preparer;
    private final VisionProvider vision;
    private final AiLocationResolver resolver;

    @Inject
    public PhotoAnalysisPipeline(ExifExtractor exif,
                                 UploadPreparer preparer,
                                 VisionProvider vision,
                                 AiLocationResolver resolver) {
        this.exif = exif;
        this.preparer = preparer;
        this.vision = vision;
        this.resolver = resolver;
    }

    public PhotoAnalysis analyze(GalleryImage image) {
        // ① 로컬 EXIF. 이 호출이 먼저 끝나야 아래로 내려갈 수 있다.
        PhotoAnalysis analysis = exif.extract(image);

        if (analysis.source == LocationSource.GPS) {
            return analysis;   // AI 호출 0회 (PRD §4.2-1).
        }

        try {
            // ② 재인코딩 + 다운스케일 + 해시(스트림 1회 읽기).
            UploadPreparer.Prepared prepared = preparer.prepare(image.contentUri);
            analysis.contentHash = prepared.contentHash;

            // ③ 업로드. 여기 도달하는 바이트는 EXIF 가 제거된 다운스케일 JPEG 뿐이다.
            RecognitionResult recognition = vision.recognize(prepared.jpeg);

            resolver.apply(analysis, recognition);
        } catch (Exception failed) {
            // S3 는 재시도하지 않는다(S4 소관). 사진 1장의 실패가 배치를 멈추면 안 되므로
            // 위치 미상으로 남기고 넘어간다 — extract() 가 이미 UNKNOWN 을 세팅해 두었다.
            Log.w(TAG, "AI 경로 실패, 위치 미상으로 둔다: " + image.displayName, failed);
        }
        return analysis;
    }

    /** 원본 접근 실패 수 — 조용한 GPS 누락은 AI 비용 폭증으로 나타난다(plan/06). */
    public int originalAccessFailures() {
        return exif.originalAccessFailures();
    }

    public void resetFailureCount() {
        exif.resetFailureCount();
    }
}
