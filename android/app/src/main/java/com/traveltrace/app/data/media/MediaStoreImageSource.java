package com.traveltrace.app.data.media;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;

import androidx.annotation.Nullable;

import com.traveltrace.app.core.AppExecutors;
import com.traveltrace.app.domain.Callback;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.hilt.android.qualifiers.ApplicationContext;

/**
 * 갤러리 이미지 목록. PRD §4.1: Photo Picker 대신 MediaStore 를 쓰는 이유는
 * 캐시·재조회에 필요한 <em>안정적 _ID</em> 때문이다.
 *
 * <p>커서에서 뽑는 건 long/문자열뿐이라 수천 장도 수 MB 미만 — 페이징 없이 1회 전량 읽고,
 * 비싼 비트맵은 Glide 가 바인드 시점에 지연 로딩한다.
 */
@Singleton
public class MediaStoreImageSource {

    private static final String[] PROJECTION = {
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.SIZE,
    };

    /**
     * 정렬·시각 폴백: DATE_TAKEN(EXIF 촬영 시각, 밀리초)이 없으면 DATE_ADDED(저장 시각,
     * 초)로 대신한다. 브라우저 다운로드·메신저 수신 사진은 DATE_TAKEN 이 비어 있어, 이
     * 폴백이 없으면 DESC 정렬에서 맨 뒤(NULL)로 밀려 최근 500장 창 밖으로 사라진다.
     */
    private static final String SORT_RECENT_FIRST =
            "COALESCE(" + MediaStore.Images.Media.DATE_TAKEN + ", "
                    + MediaStore.Images.Media.DATE_ADDED + " * 1000) DESC";

    private final Context context;
    private final AppExecutors executors;

    @Inject
    public MediaStoreImageSource(@ApplicationContext Context context, AppExecutors executors) {
        this.context = context;
        this.executors = executors;
    }

    /** 최신 촬영순으로 최대 limit 장(전체 앨범). 권한이 없으면 빈 목록이 온다(예외를 던지지 않는다). */
    public void loadRecent(int limit, Callback<List<GalleryImage>> callback) {
        loadRecent(limit, null, callback);
    }

    /**
     * 최신 촬영순으로 최대 limit 장. bucketId 가 null 이면 전체 앨범, 아니면 그 앨범(폴더)
     * 사진만. 권한이 없으면 빈 목록이 온다(예외를 던지지 않는다).
     */
    public void loadRecent(int limit, @Nullable String bucketId, Callback<List<GalleryImage>> callback) {
        executors.io().execute(() -> {
            List<GalleryImage> images = query(limit, bucketId);
            executors.mainThread().execute(() -> callback.onResult(images));
        });
    }

    /**
     * 기기의 갤러리 폴더(앨범) 목록을 사진 수 많은 순으로 돌려준다. 앨범 선택 드롭다운이
     * 쓴다. "전체 사진" 같은 합성 항목은 여기서 만들지 않는다 — 그 라벨은 한국어 문자열
     * 리소스가 필요한 UI 결정이라 데이터 계층이 아니라 호출부(ViewModel/Fragment) 몫이다.
     */
    public void loadAlbums(Callback<List<AlbumBucket>> callback) {
        executors.io().execute(() -> {
            List<AlbumBucket> albums = queryAlbums();
            executors.mainThread().execute(() -> callback.onResult(albums));
        });
    }

    /**
     * 이미 알고 있는 식별자 집합만 읽는다(ANALYZE 가 쓴다 — finding 5). SELECT 에서 이미
     * 확정된 선택이라 "최근 N 장 전체를 훑어 그중에서 고른다"가 아니라 처음부터
     * {@code _ID IN (...)} 로 걸러 쿼리한다 — 갤러리가 수만 장이어도 선택한 만큼만 읽는다.
     * ids 가 비어 있으면 쿼리 자체를 생략하고 빈 목록을 돌려준다.
     */
    public void loadByIds(List<Long> ids, Callback<List<GalleryImage>> callback) {
        executors.io().execute(() -> {
            List<GalleryImage> images = queryByIds(ids);
            executors.mainThread().execute(() -> callback.onResult(images));
        });
    }

    private List<GalleryImage> query(int limit, @Nullable String bucketId) {
        Uri collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        String sort = SORT_RECENT_FIRST;
        String selection = bucketId == null ? null : MediaStore.Images.Media.BUCKET_ID + " = ?";
        String[] args = bucketId == null ? null : new String[]{bucketId};

        try (Cursor cursor = context.getContentResolver()
                .query(collection, PROJECTION, selection, args, sort)) {
            return readRows(cursor, limit);
        } catch (SecurityException denied) {
            // 권한이 아직 없거나 철회된 상태. 빈 목록으로 조용히 끝낸다 — 안내는 UI 소관.
            return new ArrayList<>();
        }
    }

    private static final String[] BUCKET_PROJECTION = {
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME};

    private List<AlbumBucket> queryAlbums() {
        Uri collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;

        try (Cursor cursor = context.getContentResolver()
                .query(collection, BUCKET_PROJECTION, null, null, null)) {
            return aggregateBuckets(cursor);
        } catch (SecurityException denied) {
            return new ArrayList<>();
        }
    }

    /**
     * 사진 행 단위 커서를 버킷(폴더)별 개수로 접는다. 큰 앨범이 먼저 오도록 개수 내림차순
     * 정렬 — 실제로 고를 만한 앨범(카메라 등)이 위로 온다.
     */
    private List<AlbumBucket> aggregateBuckets(@Nullable Cursor cursor) {
        if (cursor == null) return new ArrayList<>();

        int idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID);
        int nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME);

        // id 로 이름을 함께 들고, 첫 등장 순서를 보존하는 카운트 맵(동점일 때 결과가
        // 흔들리지 않게 — LinkedHashMap 이 삽입 순서를 지킨다).
        Map<String, String> names = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();

        while (cursor.moveToNext()) {
            if (cursor.isNull(idCol) || cursor.isNull(nameCol)) continue;
            String id = cursor.getString(idCol);
            String name = cursor.getString(nameCol);
            names.putIfAbsent(id, name);
            counts.merge(id, 1, Integer::sum);
        }

        List<AlbumBucket> albums = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            albums.add(new AlbumBucket(entry.getKey(), names.get(entry.getKey()), entry.getValue()));
        }
        albums.sort(Comparator.comparingInt((AlbumBucket a) -> a.count).reversed());
        return albums;
    }

    private List<GalleryImage> queryByIds(List<Long> ids) {
        if (ids.isEmpty()) return new ArrayList<>();

        Uri collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        String sort = SORT_RECENT_FIRST;
        StringBuilder selection = new StringBuilder(MediaStore.Images.Media._ID).append(" IN (");
        String[] args = new String[ids.size()];
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) selection.append(',');
            selection.append('?');
            args[i] = String.valueOf(ids.get(i));
        }
        selection.append(')');

        try (Cursor cursor = context.getContentResolver()
                .query(collection, PROJECTION, selection.toString(), args, sort)) {
            return readRows(cursor, Integer.MAX_VALUE);
        } catch (SecurityException denied) {
            return new ArrayList<>();
        }
    }

    private List<GalleryImage> readRows(@Nullable Cursor cursor, int limit) {
        List<GalleryImage> images = new ArrayList<>();
        if (cursor == null) return images;

        Uri collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        int idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
        int nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME);
        int takenCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN);
        int sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE);
        // getColumnIndex(비-Throw): 이 컬럼이 없는 테스트 커서에서도 -1 로 조용히 넘어가
        // 기존 동작(DATE_TAKEN 없으면 null)을 그대로 보존한다. 실제 MediaStore 쿼리는
        // PROJECTION 에 DATE_ADDED 가 있어 폴백이 동작한다.
        int addedCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_ADDED);

        while (cursor.moveToNext() && images.size() < limit) {
            long id = cursor.getLong(idCol);
            long taken = cursor.isNull(takenCol) ? 0L : cursor.getLong(takenCol);
            // EXIF 촬영 시각이 없으면 저장 시각(DATE_ADDED, 초→밀리초)으로 폴백한다 —
            // 다운로드·수신 사진이 정렬에서 밀려나거나 시각 없음으로 사라지지 않게.
            if (taken <= 0L && addedCol >= 0 && !cursor.isNull(addedCol)) {
                taken = cursor.getLong(addedCol) * 1000L;
            }
            images.add(new GalleryImage(
                    id,
                    ContentUris.withAppendedId(collection, id),
                    cursor.getString(nameCol),
                    // 0 은 "촬영 시각 모름"이다 — 1970년으로 저장하면 정렬이 망가진다.
                    taken > 0L ? taken : null,
                    cursor.isNull(sizeCol) ? 0L : cursor.getLong(sizeCol)));
        }
        return images;
    }
}
