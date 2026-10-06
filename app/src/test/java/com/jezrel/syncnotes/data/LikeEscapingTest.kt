package com.jezrel.syncnotes.data

import com.jezrel.syncnotes.data.repository.escapeLikeQuery
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure unit test (no Android) for the search-escaping helper. */
class LikeEscapingTest {

    @Test fun emptyStaysEmpty() = assertEquals("", escapeLikeQuery(""))

    @Test fun plainTextUnchanged() = assertEquals("eggs", escapeLikeQuery("eggs"))

    @Test fun percentIsEscaped() = assertEquals("50\\%", escapeLikeQuery("50%"))

    @Test fun underscoreIsEscaped() = assertEquals("a\\_b", escapeLikeQuery("a_b"))

    @Test fun backslashIsEscaped() = assertEquals("a\\\\b", escapeLikeQuery("a\\b"))

    // Backslash must be doubled BEFORE wildcards are escaped: "\%" -> "\\" + "\%".
    @Test fun backslashIsEscapedBeforeWildcards() =
        assertEquals("\\\\\\%", escapeLikeQuery("\\%"))
}
