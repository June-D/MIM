package com.junesoon.mim.app.domain

import kotlin.test.Test
import kotlin.test.assertEquals

/** 键盘选择的游标规则：能到边界、不越界、空列表永远 -1。 */
class ListCursorTest {

    @Test
    fun enters_from_either_direction() {
        assertEquals(0, nextCursor(-1, 5, 1))
        assertEquals(4, nextCursor(-1, 5, -1))
    }

    @Test
    fun moves_and_stops_at_edges() {
        assertEquals(2, nextCursor(1, 5, 1))
        assertEquals(0, nextCursor(1, 5, -1))
        assertEquals(4, nextCursor(4, 5, 1), "到底了继续按应停住，不循环")
        assertEquals(0, nextCursor(0, 5, -1))
    }

    @Test
    fun empty_list_has_no_cursor() {
        assertEquals(-1, nextCursor(-1, 0, 1))
        assertEquals(-1, nextCursor(3, 0, -1))
    }
}
