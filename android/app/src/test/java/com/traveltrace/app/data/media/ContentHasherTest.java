package com.traveltrace.app.data.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.Uri;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;

import java.io.ByteArrayInputStream;

@RunWith(RobolectricTestRunner.class)
public class ContentHasherTest {

    private Context ctx;
    private ContentHasher hasher;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        hasher = new ContentHasher(ctx);
    }

    /**
     * 같은 Uri 를 여러 번 열어야 하는 테스트가 있으므로 registerInputStream(단일 인스턴스,
     * 한 번 소비하면 고갈)이 아니라 registerInputStreamSupplier 를 쓴다 — 매 open 마다
     * 새 스트림을 만들어 실기기의 ContentProvider 와 같은 모양이 된다.
     */
    private Uri register(String name, byte[] bytes) {
        Uri uri = Uri.parse("content://media/external/images/media/" + Math.abs(name.hashCode()));
        Shadows.shadowOf(ctx.getContentResolver())
                .registerInputStreamSupplier(uri, () -> new ByteArrayInputStream(bytes));
        return uri;
    }

    private static byte[] bytes(int size, int seed) {
        byte[] b = new byte[size];
        for (int i = 0; i < size; i++) b[i] = (byte) ((i * 31 + seed) & 0xFF);
        return b;
    }

    @Test
    public void sameContentAndSizeHashToTheSameKey() {
        byte[] content = bytes(4096, 7);
        String first = hasher.hash(register("a.jpg", content), content.length);
        String second = hasher.hash(register("b.jpg", content), content.length);

        assertEquals("같은 바이트·같은 크기는 같은 캐시 키여야 한다 — 여행을 넘나드는 hit 의 근거",
                first, second);
    }

    @Test
    public void hashIsLowercaseHexSha256() {
        byte[] content = bytes(1024, 1);
        String hash = hasher.hash(register("hex.jpg", content), content.length);

        assertEquals("SHA-256 은 32바이트 = hex 64자", 64, hash.length());
        assertTrue("소문자 hex 만 나와야 한다: " + hash, hash.matches("[0-9a-f]{64}"));
    }

    @Test
    public void differentContentHashesDifferently() {
        byte[] one = bytes(2048, 1);
        byte[] two = bytes(2048, 2);

        assertNotEquals(hasher.hash(register("one.jpg", one), one.length),
                hasher.hash(register("two.jpg", two), two.length));
    }

    @Test
    public void sizeIsPartOfTheKeyEvenWhenThePrefixMatches() {
        // 프리픽스(64KiB)가 완전히 동일하고 뒤쪽만 다른 두 파일 — 크기가 키에 섞이지
        // 않으면 서로 충돌해서 남의 분석 결과를 재사용하게 된다.
        byte[] shared = bytes(ContentHasher.PREFIX_BYTES, 3);
        Uri uri = register("tail.jpg", shared);

        assertNotEquals("프리픽스가 같아도 파일 크기가 다르면 다른 키여야 한다",
                hasher.hash(uri, shared.length),
                hasher.hash(uri, shared.length + 1_000_000L));
    }

    @Test
    public void onlyThePrefixIsRead() {
        // 프리픽스보다 큰 파일이라도 앞 64KiB 만 읽어야 한다 — 뒤쪽이 달라도 (크기가 같다면)
        // 같은 키가 나오는 것이 이 설계의 의도된 트레이드오프다.
        byte[] head = bytes(ContentHasher.PREFIX_BYTES, 5);

        byte[] longA = new byte[ContentHasher.PREFIX_BYTES + 512];
        byte[] longB = new byte[ContentHasher.PREFIX_BYTES + 512];
        System.arraycopy(head, 0, longA, 0, head.length);
        System.arraycopy(head, 0, longB, 0, head.length);
        longB[longB.length - 1] = 0x7F;

        assertEquals("앞 64KiB + 크기가 같으면 같은 키 — 전체 바이트를 읽지 않는다는 증거",
                hasher.hash(register("longA.jpg", longA), longA.length),
                hasher.hash(register("longB.jpg", longB), longB.length));
    }

    @Test
    public void unreadableUriYieldsNullInsteadOfThrowing() {
        Uri missing = Uri.parse("content://com.traveltrace.absent/999");

        assertNull("읽을 수 없으면 null — 호출부는 캐시를 건너뛰고 정상 분석을 계속한다",
                hasher.hash(missing, 123L));
    }
}
