package com.traveltrace.app.data.exif;

import androidx.annotation.Nullable;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * EXIF 촬영 시각 → UTC millis (PRD §4.2 시각 정규화).
 *
 * <p>규칙: OffsetTimeOriginal 이 있으면 그것을 쓰고, 없으면 <em>기기 타임존</em>으로
 * 폴백하되 폴백이었음을 기록한다(경고 1회의 근거). 폴백은 절대 분석을 막지 않는다
 * — plan/06 의 무타임존 폴백 계약.
 *
 * <p>여행 기준 타임존을 사용자에게 확정받는 시트는 S4 소관이다. 그 덕에 S1 은
 * 좌표→타임존 오프라인 데이터 의존성을 들이지 않는다.
 */
public final class TimeNormalizer {

    /** EXIF DateTimeOriginal 의 표준 형식. */
    private static final String EXIF_PATTERN = "yyyy:MM:dd HH:mm:ss";

    /** "+09:00" / "-05:00" 형태만 받는다. */
    private static final Pattern OFFSET = Pattern.compile("^([+-])(\\d{2}):(\\d{2})$");

    public static final class Result {
        /** UTC millis. 시각을 못 읽었으면 null. */
        @Nullable public final Long utcMillis;
        /** EXIF 오프셋 태그를 실제로 썼는지. false 면 기기 타임존 폴백이다. */
        public final boolean hasOffset;

        Result(@Nullable Long utcMillis, boolean hasOffset) {
            this.utcMillis = utcMillis;
            this.hasOffset = hasOffset;
        }
    }

    private TimeNormalizer() {}

    public static Result toUtcMillis(@Nullable String dateTimeOriginal,
                                     @Nullable String offsetOriginal,
                                     TimeZone deviceZone) {
        if (dateTimeOriginal == null || dateTimeOriginal.trim().isEmpty()) {
            return new Result(null, false);
        }

        TimeZone zone = parseOffset(offsetOriginal);
        boolean hasOffset = zone != null;
        if (zone == null) zone = deviceZone;

        SimpleDateFormat fmt = new SimpleDateFormat(EXIF_PATTERN, Locale.US);
        fmt.setTimeZone(zone);
        fmt.setLenient(false);
        try {
            Date parsed = fmt.parse(dateTimeOriginal);
            return new Result(parsed == null ? null : parsed.getTime(),
                    parsed != null && hasOffset);
        } catch (ParseException malformed) {
            return new Result(null, false);
        }
    }

    /** 못 읽는 오프셋은 null 로 돌려 기기 타임존 폴백을 타게 한다. */
    @Nullable
    private static TimeZone parseOffset(@Nullable String offsetOriginal) {
        if (offsetOriginal == null) return null;
        Matcher m = OFFSET.matcher(offsetOriginal.trim());
        if (!m.matches()) return null;
        return TimeZone.getTimeZone("GMT" + m.group(1) + m.group(2) + ":" + m.group(3));
    }
}
