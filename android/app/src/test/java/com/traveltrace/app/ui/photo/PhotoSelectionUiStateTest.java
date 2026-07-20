package com.traveltrace.app.ui.photo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.net.Uri;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Arrays;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class PhotoSelectionUiStateTest {

    private static PhotoSelectionUiState twoRealTiles() {
        List<PhotoSelectionUiState.Tile> tiles = Arrays.asList(
                new PhotoSelectionUiState.Tile(
                        0xFFDBE4EE, null, true, 11L, Uri.parse("content://media/11")),
                new PhotoSelectionUiState.Tile(
                        0xFFE8E0D6, "개선문", false, 22L, Uri.parse("content://media/22")));
        return new PhotoSelectionUiState("기간", 100, "전체 사진", tiles);
    }

    @Test
    public void toggledTileKeepsItsPhotoIdentity() {
        PhotoSelectionUiState next = twoRealTiles().withToggled(0);

        PhotoSelectionUiState.Tile toggled = next.tiles.get(0);
        assertEquals("탭해도 선택 상태만 바뀐다", false, toggled.selected);
        assertEquals("mediaStoreId 가 사라지면 저장이 엉뚱한 사진을 가리킨다",
                11L, toggled.mediaStoreId);
        assertEquals("contentUri 가 사라지면 썸네일이 색블록으로 되돌아간다",
                Uri.parse("content://media/11"), toggled.contentUri);
        assertEquals("톤 색도 보존된다", 0xFFDBE4EE, toggled.toneColor);
        assertNull(toggled.label);
    }

    @Test
    public void toggledStatePreservesTheAlbumLabel() {
        PhotoSelectionUiState next = twoRealTiles().withToggled(0);

        assertEquals("탭해도 앨범 라벨은 그대로다", "전체 사진", next.albumLabel);
    }

    @Test
    public void selectedMediaStoreIdsReturnsOnlySelectedInOrder() {
        assertEquals(Arrays.asList(11L), twoRealTiles().selectedMediaStoreIds());

        PhotoSelectionUiState bothOn = twoRealTiles().withToggled(1);
        assertEquals(Arrays.asList(11L, 22L), bothOn.selectedMediaStoreIds());
    }

    @Test
    public void fixtureTilesCarryNoUriSoTheColorBlockPathStillRenders() {
        PhotoSelectionUiState fixture =
                com.traveltrace.app.ui.preview.ScreenFixtures.photoSelection();

        assertNull("픽스처 타일엔 실제 사진이 없다 — 골든이 색블록으로 유지되는 근거",
                fixture.tiles.get(0).contentUri);
        assertEquals(18, fixture.tiles.size());
        assertEquals(16, fixture.selectedCount());
    }
}
