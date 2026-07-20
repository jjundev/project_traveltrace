package com.traveltrace.app.analysis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.traveltrace.app.core.model.GeoPoint;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * "확신에 찬 오답 핀"을 막는 마지막 관문 (PRD §4.3).
 *
 * <p>plan/09 의 문구는 "결과가 다수면 강등"이지만 그대로 구현하면 못 쓴다 — Places 는
 * 명확한 질의에도 주변 시설을 함께 돌려주므로 거의 모든 사진이 NAME_ONLY 가 된다.
 * 그래서 개수가 아니라 <em>흩어짐</em>으로 판정한다: 후보가 한 도시 안에 모여 있으면
 * 같은 장소를 가리키는 것이고, 대륙을 건너 흩어져 있으면 진짜 동명 지명이다.
 */
@RunWith(RobolectricTestRunner.class)
public class DisambiguatorTest {

    private static final GeoPoint PARIS_FR = new GeoPoint(48.8584, 2.2945);
    private static final GeoPoint PARIS_TX = new GeoPoint(33.6609, -95.5555);
    private static final GeoPoint LOUVRE = new GeoPoint(48.8606, 2.3376);

    @Test
    public void singleCandidateIsAccepted() {
        GeoPoint resolved = Disambiguator.resolve(Collections.singletonList(PARIS_FR));
        assertNotNull(resolved);
        assertEquals(48.8584, resolved.lat, 1e-9);
    }

    @Test
    public void emptyMeansUnresolvable() {
        assertNull(Disambiguator.resolve(Collections.<GeoPoint>emptyList()));
    }

    @Test
    public void nearbyCandidatesCollapseToTheFirst() {
        // 에펠탑과 루브르는 약 3km. Places 가 이렇게 돌려주는 건 동명 지명이 아니라
        // 같은 동네의 인접 시설이다 — 여기서 강등하면 정상 인식이 전부 죽는다.
        GeoPoint resolved = Disambiguator.resolve(Arrays.asList(PARIS_FR, LOUVRE));
        assertNotNull(resolved);
        assertEquals("1번 후보(Places 랭킹 최상위)를 채택한다", 48.8584, resolved.lat, 1e-9);
    }

    @Test
    public void farApartCandidatesAreAmbiguous() {
        // 파리(프랑스) vs 파리(텍사스) — 약 7900km. 어느 쪽을 찍어도 절반은 오답이다.
        assertNull("동명 지명은 오답 핀 대신 NAME_ONLY 로 강등한다",
                Disambiguator.resolve(Arrays.asList(PARIS_FR, PARIS_TX)));
    }

    @Test
    public void oneDistantOutlierIsEnoughToAbstain() {
        assertNull(Disambiguator.resolve(Arrays.asList(PARIS_FR, LOUVRE, PARIS_TX)));
    }

    @Test
    public void radiusIsMeasuredFromTheTopCandidate() {
        // 상위 후보 기준 반경 안이면 나머지 후보끼리 얼마나 떨어졌든 상관없다 —
        // 채택할 좌표는 어차피 1번 후보이므로 그 주변의 일관성만 따진다.
        double justInside = Disambiguator.AMBIGUITY_RADIUS_KM - 1d;
        GeoPoint near = new GeoPoint(PARIS_FR.lat + justInside / 111d, PARIS_FR.lng);
        assertNotNull(Disambiguator.resolve(Arrays.asList(PARIS_FR, near)));
    }

    @Test
    public void handlesAntimeridianWithoutFalseAmbiguity() {
        // 경도 179.9 와 -179.9 는 약 22km 떨어져 있다. 단순 뺄셈으로 거리를 재면
        // 359.8도로 계산되어 멀쩡한 인식이 강등된다 — 하버사인이 필요한 이유.
        List<GeoPoint> across = new ArrayList<>();
        across.add(new GeoPoint(1.0, 179.9));
        across.add(new GeoPoint(1.0, -179.9));
        assertNotNull("날짜변경선을 건너도 가까운 건 가까운 것이다",
                Disambiguator.resolve(across));
    }
}
