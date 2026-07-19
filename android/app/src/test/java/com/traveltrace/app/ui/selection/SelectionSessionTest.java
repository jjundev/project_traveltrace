package com.traveltrace.app.ui.selection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class SelectionSessionTest {

    @Test
    public void startsEmpty() {
        assertTrue(new SelectionSession().isEmpty());
    }

    @Test
    public void putThenReadRoundTrips() {
        SelectionSession session = new SelectionSession();
        session.put(Arrays.asList(11L, 22L, 33L));

        assertFalse(session.isEmpty());
        assertEquals(Arrays.asList(11L, 22L, 33L), session.ids());
    }

    @Test
    public void returnedListIsADefensiveCopy() {
        SelectionSession session = new SelectionSession();
        session.put(Arrays.asList(11L));

        List<Long> ids = session.ids();
        ids.add(99L);

        assertEquals("외부에서 목록을 바꿔도 세션은 그대로여야 한다",
                Arrays.asList(11L), session.ids());
    }

    @Test
    public void clearEmptiesTheSession() {
        SelectionSession session = new SelectionSession();
        session.put(Arrays.asList(11L));
        session.clear();

        assertTrue(session.isEmpty());
        assertTrue(session.ids().isEmpty());
    }
}
