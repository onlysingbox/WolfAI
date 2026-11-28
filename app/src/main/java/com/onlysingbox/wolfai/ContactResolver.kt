package com.onlysingbox.wolfai

data class ContactPhone(val name: String, val number: String)

sealed class ContactLookup {
    data class Match(val contact: ContactPhone) : ContactLookup()
    data class Ambiguous(val contacts: List<ContactPhone>) : ContactLookup()
    data object NotFound : ContactLookup()
}

object ContactResolver {
    fun resolve(query: String, contacts: List<ContactPhone>): ContactLookup {
        val normalized = query.trim().replace(Regex("\\s+"), " ")
        if (normalized.isBlank()) return ContactLookup.NotFound
        val usable = contacts.filter { it.name.isNotBlank() && it.number.isNotBlank() }
            .distinctBy { it.number.filter(Char::isDigit) }
        val exact = usable.filter { it.name.trim().equals(normalized, ignoreCase = true) }
        val candidates = if (exact.isNotEmpty()) exact else usable.filter {
            it.name.contains(normalized, ignoreCase = true)
        }
        return when (candidates.size) {
            0 -> ContactLookup.NotFound
            1 -> ContactLookup.Match(candidates.single())
            else -> ContactLookup.Ambiguous(candidates.take(8))
        }
    }
}
