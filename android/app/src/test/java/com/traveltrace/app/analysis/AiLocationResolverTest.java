package com.traveltrace.app.analysis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.traveltrace.app.core.model.GeoPoint;
import com.traveltrace.app.core.model.GeocodeQuery;
import com.traveltrace.app.core.model.LocationClassification;
import com.traveltrace.app.core.model.LocationSource;
import com.traveltrace.app.core.model.RecognitionResult;
import com.traveltrace.app.domain.Geocoder;
import com.traveltrace.app.domain.model.PhotoAnalysis;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * PRD §4.6 의 분류 3종이 갈리는 지점. 여기가 틀리면 지도에 확신에 찬 오답 핀이 찍히거나
 * (반대로) 멀쩡한 인식이 전부 위치 미상으로 사라진다.
 */
@RunWith(RobolectricTestRunner.class)
public class AiLocationResolverTest {

    private static final GeoPoint EIFFEL = new GeoPoint(48.8584, 2.2945);
    private static final GeoPoint PARIS_TX = new GeoPoint(33.6609, -95.5555);

    private static PhotoAnalysis blank(Long takenAtUtc) {
        PhotoAnalysis a = new PhotoAnalysis();
        a.mediaStoreId = 1L;
        a.displayName = "IMG_0001.jpg";
        a.takenAtUtc = takenAtUtc;
        a.source = LocationSource.NONE;
        a.classification = LocationClassification.UNKNOWN;
        return a;
    }

    /** 테스트 안에서만 쓰는 손수 만든 페이크(저장소 규약: 모킹 라이브러리 없음). */
    private static final class FakeGeocoder implements Geocoder {
        private final List<GeoPoint> results;
        GeocodeQuery received;
        RuntimeException toThrow;

        FakeGeocoder(List<GeoPoint> results) { this.results = results; }

        @Override
        public List<GeoPoint> geocode(GeocodeQuery query) {
            received = query;
            if (toThrow != null) throw toThrow;
            return results;
        }
    }

    @Test
    public void confidentSingleMatchBecomesAnAiPlacedStop() throws Exception {
        PhotoAnalysis a = blank(1_700_000_000_000L);
        FakeGeocoder geocoder = new FakeGeocoder(Collections.singletonList(EIFFEL));

        new AiLocationResolver(geocoder).apply(a, new RecognitionResult("에펠탑", "파리", "프랑스", 0.92));

        assertEquals(LocationSource.AI, a.source);
        assertEquals(LocationClassification.PLACED, a.classification);
        assertEquals(48.8584, a.lat, 1e-9);
        assertEquals(2.2945, a.lng, 1e-9);
        assertEquals("에펠탑", a.landmarkName);
        assertEquals(0.92d, a.confidence, 1e-9);
    }

    @Test
    public void placedWithoutATimestampIsNoTime() throws Exception {
        PhotoAnalysis a = blank(null);

        new AiLocationResolver(new FakeGeocoder(Collections.singletonList(EIFFEL)))
                .apply(a, new RecognitionResult("에펠탑", null, null, 0.9));

        assertEquals("좌표는 있지만 경로 순서에 넣을 수 없다(PRD §4.6)",
                LocationClassification.NO_TIME, a.classification);
        assertEquals(LocationSource.AI, a.source);
    }

    @Test
    public void lowConfidenceKeepsTheNameButDropsTheCoordinates() throws Exception {
        PhotoAnalysis a = blank(1_700_000_000_000L);
        FakeGeocoder geocoder = new FakeGeocoder(Collections.singletonList(EIFFEL));

        new AiLocationResolver(geocoder).apply(a, new RecognitionResult("에펠탑", null, null, 0.2));

        assertEquals(LocationClassification.NAME_ONLY, a.classification);
        assertEquals("이름은 살린다 — '이름만'은 '위치 미상'과 별개 분류다", "에펠탑", a.landmarkName);
        assertNull("확신 없는 좌표를 찍느니 안 찍는다", a.lat);
        assertNull(a.lng);
        assertEquals(LocationSource.NONE, a.source);
        assertNull("저신뢰면 지오코딩 호출 자체를 안 한다 — 낭비다", geocoder.received);
    }

    @Test
    public void homonymsDegradeToNameOnly() throws Exception {
        PhotoAnalysis a = blank(1_700_000_000_000L);

        new AiLocationResolver(new FakeGeocoder(Arrays.asList(EIFFEL, PARIS_TX)))
                .apply(a, new RecognitionResult(null, "파리", null, 0.9));

        assertEquals(LocationClassification.NAME_ONLY, a.classification);
        assertNull(a.lat);
        assertEquals("파리", a.city);
    }

    @Test
    public void zeroResultDegradesToNameOnly() throws Exception {
        PhotoAnalysis a = blank(1_700_000_000_000L);

        new AiLocationResolver(new FakeGeocoder(Collections.<GeoPoint>emptyList()))
                .apply(a, new RecognitionResult("있을 리 없는 가게", null, null, 0.9));

        assertEquals("좌표화 실패는 이름을 보존한 채 강등", LocationClassification.NAME_ONLY, a.classification);
        assertEquals("있을 리 없는 가게", a.landmarkName);
    }

    @Test
    public void unrecognizedPhotoStaysUnknown() throws Exception {
        PhotoAnalysis a = blank(1_700_000_000_000L);
        FakeGeocoder geocoder = new FakeGeocoder(Collections.singletonList(EIFFEL));

        new AiLocationResolver(geocoder).apply(a, new RecognitionResult(null, null, null, 0d));

        assertEquals("실내·음식 사진은 위치 미상이 정상 결과다",
                LocationClassification.UNKNOWN, a.classification);
        assertNull(a.landmarkName);
        assertNull(geocoder.received);
    }

    @Test
    public void poiNamesGoToPlacesAndAdminNamesToGeocoding() throws Exception {
        FakeGeocoder poi = new FakeGeocoder(Collections.singletonList(EIFFEL));
        new AiLocationResolver(poi).apply(blank(1L), new RecognitionResult("에펠탑", "파리", null, 0.9));
        assertEquals(GeocodeQuery.Poi.class, poi.received.getClass());

        FakeGeocoder admin = new FakeGeocoder(Collections.singletonList(EIFFEL));
        new AiLocationResolver(admin).apply(blank(1L), new RecognitionResult(null, "파리", "프랑스", 0.9));
        assertEquals(GeocodeQuery.AdministrativePlace.class, admin.received.getClass());
    }

    @Test
    public void geocoderFailurePropagatesSoS4CanRetryIt() {
        FakeGeocoder geocoder = new FakeGeocoder(Collections.singletonList(EIFFEL));
        geocoder.toThrow = new RuntimeException("boom");
        try {
            new AiLocationResolver(geocoder).apply(blank(1L),
                    new RecognitionResult("에펠탑", null, null, 0.9));
            org.junit.Assert.fail("일시적 실패를 NAME_ONLY 로 삼키면 재시도 기회가 사라진다");
        } catch (Exception expected) {
            assertEquals("boom", expected.getMessage());
        }
    }
}
