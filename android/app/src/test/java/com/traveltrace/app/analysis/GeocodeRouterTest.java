package com.traveltrace.app.analysis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.traveltrace.app.core.model.GeocodeQuery;
import com.traveltrace.app.core.model.RecognitionResult;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * PRD §4.4 의 핵심 실패 모드를 고정한다: POI 이름을 Geocoding 으로 보내면 zero result 가
 * 급증하고, 행정 지명을 Places 로 보내면 엉뚱한 상호가 걸린다. 어느 API 로 갈지는
 * 네트워크가 아니라 이 함수가 결정하므로 여기서 전부 검증된다.
 */
@RunWith(RobolectricTestRunner.class)
public class GeocodeRouterTest {

    private static RecognitionResult r(String landmark, String city, String country, double conf) {
        return new RecognitionResult(landmark, city, country, conf);
    }

    @Test
    public void landmarkNameRoutesToPlaces() {
        GeocodeQuery q = GeocodeRouter.queryFor(r("에펠탑", "파리", "프랑스", 0.9));
        assertTrue("POI 는 Places Text Search 로 — 의미 기반 질의에 강하다",
                q instanceof GeocodeQuery.Poi);
        assertEquals("에펠탑", ((GeocodeQuery.Poi) q).name);
    }

    @Test
    public void cityAndCountryRouteToGeocoding() {
        GeocodeQuery q = GeocodeRouter.queryFor(r(null, "파리", "프랑스", 0.8));
        assertTrue("행정 지명은 Geocoding 으로", q instanceof GeocodeQuery.AdministrativePlace);
        assertEquals("파리, 프랑스", ((GeocodeQuery.AdministrativePlace) q).text);
    }

    @Test
    public void cityAloneStillRoutesToGeocoding() {
        GeocodeQuery q = GeocodeRouter.queryFor(r(null, "파리", null, 0.8));
        assertEquals("파리", ((GeocodeQuery.AdministrativePlace) q).text);
    }

    @Test
    public void countryAloneStillRoutesToGeocoding() {
        GeocodeQuery q = GeocodeRouter.queryFor(r(null, null, "프랑스", 0.8));
        assertEquals("프랑스", ((GeocodeQuery.AdministrativePlace) q).text);
    }

    @Test
    public void landmarkWinsOverCityWhenBothArePresent() {
        GeocodeQuery q = GeocodeRouter.queryFor(r("루브르 박물관", "파리", "프랑스", 0.9));
        assertTrue("더 구체적인 위치를 버리고 도시 중심점을 찍을 이유가 없다",
                q instanceof GeocodeQuery.Poi);
    }

    @Test
    public void noNamesMeansNothingToGeocode() {
        assertNull(GeocodeRouter.queryFor(r(null, null, null, 0.9)));
    }

    @Test
    public void blankNamesAreTreatedAsAbsent() {
        assertNull(GeocodeRouter.queryFor(r("", "   ", "", 0.9)));
    }

    @Test
    public void lowConfidenceIsNotThisFunctionsJob() {
        // 저신뢰 강등(NAME_ONLY)은 AiLocationResolver 가 한다 — 여기서 null 을 돌려주면
        // 이름을 보존한 채 강등한다는 구분(NAME_ONLY vs UNKNOWN)이 무너진다.
        assertTrue(GeocodeRouter.queryFor(r("에펠탑", null, null, 0.01)) instanceof GeocodeQuery.Poi);
    }

    @Test
    public void confidenceThresholdIsUsablyLenient() {
        assertTrue("임계값이 너무 높으면 대부분이 NAME_ONLY 로 떨어져 AI 가 무의미해진다",
                GeocodeRouter.MIN_CONFIDENCE > 0d && GeocodeRouter.MIN_CONFIDENCE < 0.8d);
    }
}
