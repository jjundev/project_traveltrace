package com.traveltrace.app.data.exif;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.TimeZone;

public class TimeNormalizerTest {

    private static final TimeZone SEOUL = TimeZone.getTimeZone("Asia/Seoul");

    @Test
    public void offsetTagWinsOverTheDeviceZone() {
        TimeNormalizer.Result r = TimeNormalizer.toUtcMillis(
                "2024:06:12 10:12:00", "+02:00", SEOUL);

        assertTrue(r.hasOffset);
        // 2024-06-12T10:12:00+02:00 == 2024-06-12T08:12:00Z
        assertEquals(Long.valueOf(1_718_179_920_000L), r.utcMillis);
    }

    @Test
    public void missingOffsetFallsBackToTheDeviceZone() {
        TimeNormalizer.Result r = TimeNormalizer.toUtcMillis(
                "2024:06:12 10:12:00", null, SEOUL);

        assertFalse("폴백이었음을 기록해야 경고를 띄울 수 있다", r.hasOffset);
        // 2024-06-12T10:12:00+09:00 == 2024-06-12T01:12:00Z
        assertEquals(Long.valueOf(1_718_154_720_000L), r.utcMillis);
    }

    @Test
    public void negativeOffsetIsHandled() {
        TimeNormalizer.Result r = TimeNormalizer.toUtcMillis(
                "2024:06:12 10:12:00", "-05:00", SEOUL);

        assertTrue(r.hasOffset);
        // 2024-06-12T10:12:00-05:00 == 2024-06-12T15:12:00Z
        assertEquals(Long.valueOf(1_718_205_120_000L), r.utcMillis);
    }

    @Test
    public void absentDateTimeYieldsNullNotZero() {
        TimeNormalizer.Result r = TimeNormalizer.toUtcMillis(null, "+02:00", SEOUL);

        assertNull("시각을 모르면 null 이다 — 0(1970)으로 채우면 정렬이 망가진다", r.utcMillis);
        assertFalse(r.hasOffset);
    }

    @Test
    public void malformedDateTimeYieldsNull() {
        assertNull(TimeNormalizer.toUtcMillis("어제 오후", null, SEOUL).utcMillis);
        assertNull(TimeNormalizer.toUtcMillis("", null, SEOUL).utcMillis);
    }

    @Test
    public void malformedOffsetDegradesToTheDeviceZone() {
        TimeNormalizer.Result r = TimeNormalizer.toUtcMillis(
                "2024:06:12 10:12:00", "이상한값", SEOUL);

        assertFalse("오프셋을 못 읽으면 폴백으로 취급한다", r.hasOffset);
        assertEquals(Long.valueOf(1_718_154_720_000L), r.utcMillis);
    }
}
