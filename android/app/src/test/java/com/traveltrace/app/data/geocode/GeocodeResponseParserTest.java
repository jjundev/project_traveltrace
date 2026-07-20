package com.traveltrace.app.data.geocode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.traveltrace.app.core.model.GeoPoint;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.List;

/**
 * zero-result 와 에러 응답은 <em>빈 리스트</em>로 수렴해야 한다 — 호출자가 그걸
 * NAME_ONLY 로 강등한다(plan/09). 여기서 예외를 던지면 zero-result 가 재시도 대상으로
 * 잘못 분류되어 같은 실패에 돈을 세 번 쓴다.
 */
@RunWith(RobolectricTestRunner.class)
public class GeocodeResponseParserTest {

    private static JsonObject json(String raw) {
        return JsonParser.parseString(raw).getAsJsonObject();
    }

    // ---------- Places (New) Text Search ----------

    @Test
    public void placesParsesLocationsInRankOrder() {
        List<GeoPoint> points = PlacesResponseParser.parse(json(
                "{\"places\":["
                        + "{\"location\":{\"latitude\":48.8584,\"longitude\":2.2945}},"
                        + "{\"location\":{\"latitude\":48.8606,\"longitude\":2.3376}}]}"));
        assertEquals(2, points.size());
        assertEquals("Places 랭킹 순서를 보존해야 Disambiguator 가 1번을 채택한다",
                48.8584, points.get(0).lat, 1e-9);
        assertEquals(2.2945, points.get(0).lng, 1e-9);
    }

    @Test
    public void placesZeroResultIsAnEmptyListNotAnError() {
        assertTrue(PlacesResponseParser.parse(json("{}")).isEmpty());
        assertTrue(PlacesResponseParser.parse(json("{\"places\":[]}")).isEmpty());
    }

    @Test
    public void placesSkipsEntriesMissingCoordinates() {
        List<GeoPoint> points = PlacesResponseParser.parse(json(
                "{\"places\":[{\"displayName\":{\"text\":\"어딘가\"}},"
                        + "{\"location\":{\"latitude\":1.0,\"longitude\":2.0}}]}"));
        assertEquals("좌표 없는 항목을 0,0 으로 메우면 금지된 (0,0) 핀이 생긴다",
                1, points.size());
        assertEquals(1.0, points.get(0).lat, 1e-9);
    }

    @Test
    public void placesLocationNotAnObjectYieldsEmptyListWithoutThrowing() {
        assertTrue("location 이 객체가 아니면 raw cast 가 ClassCastException 을 던진다",
                PlacesResponseParser.parse(json("{\"places\":[{\"location\":\"x\"}]}")).isEmpty());
    }

    @Test
    public void placesSkipsMalformedEntryButKeepsValidOne() {
        List<GeoPoint> points = PlacesResponseParser.parse(json(
                "{\"places\":[{\"location\":\"x\"},"
                        + "{\"location\":{\"latitude\":1.0,\"longitude\":2.0}}]}"));
        assertEquals("형식이 이상한 항목 하나 때문에 전체를 포기하면 안 된다 — 건너뛰고 계속한다",
                1, points.size());
        assertEquals(1.0, points.get(0).lat, 1e-9);
        assertEquals(2.0, points.get(0).lng, 1e-9);
    }

    @Test
    public void placesSkipsEntryWithNonNumericLatitudeStringWithoutThrowing() {
        assertTrue("JsonPrimitive 가 문자열을 감싸면 getAsDouble() 이 내부적으로 "
                        + "Double.parseDouble 을 호출해 NumberFormatException 을 던진다",
                PlacesResponseParser.parse(json(
                        "{\"places\":[{\"location\":{\"latitude\":\"nope\",\"longitude\":2.0}}]}"))
                        .isEmpty());
    }

    @Test
    public void placesSkipsEntryWithNullLatitudeWithoutThrowing() {
        assertTrue("latitude 가 present-but-null 이면 JsonNull.getAsDouble() 이 던진다",
                PlacesResponseParser.parse(json(
                        "{\"places\":[{\"location\":{\"latitude\":null,\"longitude\":2.0}}]}"))
                        .isEmpty());
    }

    // ---------- Geocoding ----------

    @Test
    public void geocodingParsesResults() {
        List<GeoPoint> points = GeocodingResponseParser.parse(json(
                "{\"status\":\"OK\",\"results\":[{\"geometry\":{\"location\":"
                        + "{\"lat\":48.8566,\"lng\":2.3522}}}]}"));
        assertEquals(1, points.size());
        assertEquals(48.8566, points.get(0).lat, 1e-9);
        assertEquals(2.3522, points.get(0).lng, 1e-9);
    }

    @Test
    public void geocodingZeroResultsIsAnEmptyList() {
        assertTrue(GeocodingResponseParser.parse(
                json("{\"status\":\"ZERO_RESULTS\",\"results\":[]}")).isEmpty());
    }

    @Test
    public void geocodingNonOkStatusYieldsNothing() {
        assertTrue("REQUEST_DENIED 에서 results 를 읽으면 낡은/부분 데이터를 찍는다",
                GeocodingResponseParser.parse(json(
                        "{\"status\":\"REQUEST_DENIED\",\"error_message\":\"bad key\"}")).isEmpty());
    }

    @Test
    public void geocodingReturnsAllCandidatesForHomonymDetection() {
        List<GeoPoint> points = GeocodingResponseParser.parse(json(
                "{\"status\":\"OK\",\"results\":["
                        + "{\"geometry\":{\"location\":{\"lat\":48.85,\"lng\":2.35}}},"
                        + "{\"geometry\":{\"location\":{\"lat\":33.66,\"lng\":-95.55}}}]}"));
        assertEquals("후보를 잘라내면 Disambiguator 가 동명 지명을 볼 수 없다",
                2, points.size());
    }

    @Test
    public void geocodingNullStatusYieldsEmptyListWithoutThrowing() {
        assertTrue("status 가 null 이면 JsonNull.getAsString() 이 UnsupportedOperationException 을 던진다",
                GeocodingResponseParser.parse(json("{\"status\":null}")).isEmpty());
    }

    @Test
    public void geocodingGeometryNotAnObjectYieldsEmptyListWithoutThrowing() {
        assertTrue("geometry 가 객체가 아니면 raw cast 가 ClassCastException 을 던진다",
                GeocodingResponseParser.parse(json(
                        "{\"status\":\"OK\",\"results\":[{\"geometry\":\"x\"}]}")).isEmpty());
    }

    @Test
    public void geocodingSkipsEntryWithNullLatWithoutThrowing() {
        assertTrue("lat 이 present-but-null 이면 JsonNull.getAsDouble() 이 던진다",
                GeocodingResponseParser.parse(json(
                        "{\"status\":\"OK\",\"results\":[{\"geometry\":{\"location\":"
                                + "{\"lat\":null,\"lng\":2.0}}}]}")).isEmpty());
    }

    @Test
    public void geocodingSkipsMalformedEntryButKeepsValidOne() {
        List<GeoPoint> points = GeocodingResponseParser.parse(json(
                "{\"status\":\"OK\",\"results\":["
                        + "{\"geometry\":\"x\"},"
                        + "{\"geometry\":{\"location\":{\"lat\":48.85,\"lng\":2.35}}}]}"));
        assertEquals("형식이 이상한 항목 하나 때문에 전체를 포기하면 안 된다 — 건너뛰고 계속한다",
                1, points.size());
        assertEquals(48.85, points.get(0).lat, 1e-9);
        assertEquals(2.35, points.get(0).lng, 1e-9);
    }

    @Test
    public void geocodingLocationNotAnObjectYieldsEmptyListWithoutThrowing() {
        assertTrue("location 이 객체가 아니면 raw cast 가 ClassCastException 을 던진다",
                GeocodingResponseParser.parse(json(
                        "{\"status\":\"OK\",\"results\":[{\"geometry\":{\"location\":\"x\"}}]}"))
                        .isEmpty());
    }

    @Test
    public void geocodingSkipsEntryWithNonNumericLatStringWithoutThrowing() {
        assertTrue("JsonPrimitive 가 문자열을 감싸면 getAsDouble() 이 내부적으로 "
                        + "Double.parseDouble 을 호출해 NumberFormatException 을 던진다",
                GeocodingResponseParser.parse(json(
                        "{\"status\":\"OK\",\"results\":[{\"geometry\":{\"location\":"
                                + "{\"lat\":\"not-a-number\",\"lng\":2.0}}}]}"))
                        .isEmpty());
    }

    @Test
    public void geocodingSkipsEntryWithBooleanLatWithoutThrowing() {
        assertTrue("JsonPrimitive 가 Boolean 을 감싸면 getAsDouble() 이 \"true\"/\"false\" 문자열을 "
                        + "parseDouble 에 넘겨 NumberFormatException 을 던진다",
                GeocodingResponseParser.parse(json(
                        "{\"status\":\"OK\",\"results\":[{\"geometry\":{\"location\":"
                                + "{\"lat\":true,\"lng\":2.0}}}]}"))
                        .isEmpty());
    }

    @Test
    public void geocodingSkipsNonNumericLatEntryButKeepsValidOne() {
        List<GeoPoint> points = GeocodingResponseParser.parse(json(
                "{\"status\":\"OK\",\"results\":["
                        + "{\"geometry\":{\"location\":{\"lat\":\"not-a-number\",\"lng\":2.0}}},"
                        + "{\"geometry\":{\"location\":{\"lat\":48.85,\"lng\":2.35}}}]}"));
        assertEquals("숫자가 아닌 lat 항목 하나 때문에 전체를 포기하면 안 된다 — 건너뛰고 계속한다",
                1, points.size());
        assertEquals(48.85, points.get(0).lat, 1e-9);
        assertEquals(2.35, points.get(0).lng, 1e-9);
    }
}
