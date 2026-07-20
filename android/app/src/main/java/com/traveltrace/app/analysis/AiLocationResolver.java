package com.traveltrace.app.analysis;

import com.traveltrace.app.core.model.GeoPoint;
import com.traveltrace.app.core.model.GeocodeQuery;
import com.traveltrace.app.core.model.LocationClassification;
import com.traveltrace.app.core.model.LocationSource;
import com.traveltrace.app.core.model.RecognitionResult;
import com.traveltrace.app.domain.Geocoder;
import com.traveltrace.app.domain.model.PhotoAnalysis;

import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * 인식 결과 → 최종 분류 (PRD §4.6). 세 갈래로 갈린다:
 *
 * <ul>
 *   <li><b>PLACED</b> — 고신뢰 + 좌표화 성공 + 촬영 시각 있음. 지도·경로에 오른다.</li>
 *   <li><b>NAME_ONLY</b> — 이름은 얻었지만 믿고 찍을 좌표가 없음(저신뢰·동명 지명·
 *       zero result). 이름은 <em>보존</em>하고 지도에서만 뺀다.</li>
 *   <li><b>UNKNOWN</b> — 인식 자체가 실패. 실내·음식·추상 사진에서 정상적으로 발생한다.</li>
 * </ul>
 *
 * <p><b>일시적 실패는 삼키지 않는다.</b> 지오코딩이 던지면 그대로 전파해 S4 의 재시도가
 * 붙을 수 있게 한다 — NAME_ONLY 로 조용히 강등하면 네트워크 끊김 한 번에 여행 전체가
 * 영구히 "이름만"으로 저장된다.
 */
@Singleton
public class AiLocationResolver {

    private final Geocoder geocoder;

    @Inject
    public AiLocationResolver(Geocoder geocoder) {
        this.geocoder = geocoder;
    }

    /** {@code target} 을 제자리에서 갱신한다. GPS 사진에는 호출되지 않는다. */
    public void apply(PhotoAnalysis target, RecognitionResult recognition) throws Exception {
        // 이름은 분류와 무관하게 항상 기록한다 — NAME_ONLY 도 UNKNOWN 도 화면에서
        // 이름을 보여줄 수 있어야 하고(S6), 재분석 없이 나중에 수동 핀을 찍을 근거가 된다.
        target.landmarkName = recognition.landmarkName;
        target.city = recognition.city;
        target.country = recognition.country;
        target.confidence = recognition.confidence;

        GeocodeQuery query = GeocodeRouter.queryFor(recognition);
        if (query == null) {
            target.classification = LocationClassification.UNKNOWN;
            return;   // 좌표화할 이름이 없다 = 인식 실패.
        }
        if (recognition.confidence < GeocodeRouter.MIN_CONFIDENCE) {
            // 저신뢰면 지오코딩을 호출하지도 않는다 — 어차피 안 찍을 좌표에 돈을 쓰지 않는다.
            target.classification = LocationClassification.NAME_ONLY;
            return;
        }

        List<GeoPoint> candidates = geocoder.geocode(query);
        GeoPoint resolved = Disambiguator.resolve(candidates);
        if (resolved == null) {
            // zero result 이거나 동명 지명. 이름은 이미 위에서 보존했다.
            target.classification = LocationClassification.NAME_ONLY;
            return;
        }

        target.lat = resolved.lat;
        target.lng = resolved.lng;
        target.source = LocationSource.AI;
        target.classification = target.takenAtUtc == null
                ? LocationClassification.NO_TIME
                : LocationClassification.PLACED;
    }
}
