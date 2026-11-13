package com.hermes.chatandroid

import com.hermes.chatandroid.net.ThinkFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThinkFilterTest {
    @Test fun plainTextUntouched() = assertEquals("hello", ThinkFilter.strip("hello"))

    @Test fun removesClosedBlock() = assertEquals("ab", ThinkFilter.strip("a<think>x</think>b"))

    @Test fun removesMultipleBlocks() = assertEquals("ac", ThinkFilter.strip("<think>1</think>a<think>2</think>c"))

    @Test fun unclosedHidesRest() = assertEquals("a", ThinkFilter.strip("a<think>reasoning..."))

    @Test fun partialOpenTagHeldBack() {
        assertEquals("a", ThinkFilter.strip("a<thi"))
        assertEquals("a", ThinkFilter.strip("a<"))
        assertEquals("a <b", ThinkFilter.strip("a <b"))
    }

    @Test fun thinkOnlyIsEmpty() = assertEquals("", ThinkFilter.strip("<think>only</think>"))

    @Test fun hasOpenThink() {
        assertTrue(ThinkFilter.hasOpenThink("x<think>y"))
        assertFalse(ThinkFilter.hasOpenThink("x<think>y</think>"))
        assertFalse(ThinkFilter.hasOpenThink("plain"))
    }
}
