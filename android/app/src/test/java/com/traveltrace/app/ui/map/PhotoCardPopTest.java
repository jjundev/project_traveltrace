package com.traveltrace.app.ui.map;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.view.View;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * 팝의 <em>시작 상태</em>만 검증한다. 애니메이션의 끝 상태는 Robolectric 에서 프레임 구동
 * 방식에 따라 흔들려 간헐 실패가 되기 쉬우므로, 결정적으로 관찰 가능한 것 — "play() 직후
 * 카드가 작고 투명하다", "다시 부르면 다시 작아진다" — 만 어서션한다. 실제 스프링 느낌은
 * Task 6 의 실기기 체크리스트에서 눈으로 본다.
 */
@RunWith(RobolectricTestRunner.class)
public class PhotoCardPopTest {

    private View card;

    @Before
    public void setUp() {
        Context ctx = ApplicationProvider.getApplicationContext();
        card = new View(ctx);
    }

    @Test
    public void playArmsTheCardSmallAndTransparent() {
        PhotoCardPop.play(card);

        assertEquals(0f, card.getAlpha(), 0.001f);
        assertEquals(PhotoCardPop.START_SCALE, card.getScaleX(), 0.001f);
        assertEquals(PhotoCardPop.START_SCALE, card.getScaleY(), 0.001f);
    }

    @Test
    public void playingAgainRearmsFromTheStart() {
        PhotoCardPop.play(card);
        card.setAlpha(1f);
        card.setScaleX(1f);
        card.setScaleY(1f);

        PhotoCardPop.play(card);

        assertEquals(0f, card.getAlpha(), 0.001f);
        assertEquals(PhotoCardPop.START_SCALE, card.getScaleX(), 0.001f);
    }
}
