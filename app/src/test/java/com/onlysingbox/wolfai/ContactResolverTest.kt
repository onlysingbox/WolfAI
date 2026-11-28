package com.onlysingbox.wolfai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactResolverTest {
    private val contacts = listOf(
        ContactPhone("Ali Rezaei", "+989121111111"),
        ContactPhone("Ali Mohammadi", "+989122222222"),
        ContactPhone("Sara", "+989123333333"),
        ContactPhone("Sara", "+989123333333")
    )

    @Test
    fun exactUniqueNameWinsOverPartialMatches() {
        val result = ContactResolver.resolve("Sara", contacts)
        assertTrue(result is ContactLookup.Match)
        assertEquals("+989123333333", (result as ContactLookup.Match).contact.number)
    }

    @Test
    fun ambiguousPartialNameNeverPicksTheFirstPhone() {
        val result = ContactResolver.resolve("Ali", contacts)
        assertTrue(result is ContactLookup.Ambiguous)
        assertEquals(2, (result as ContactLookup.Ambiguous).contacts.size)
    }

    @Test
    fun unknownOrBlankNamesAreNotFound() {
        assertTrue(ContactResolver.resolve("", contacts) is ContactLookup.NotFound)
        assertTrue(ContactResolver.resolve("Nobody", contacts) is ContactLookup.NotFound)
    }
}
