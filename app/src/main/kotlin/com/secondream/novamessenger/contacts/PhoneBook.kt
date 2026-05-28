package com.secondream.novamessenger.contacts

import android.content.Context
import android.provider.ContactsContract
import com.secondream.novamessenger.td.PhoneContact

object PhoneBook {
    /**
     * Read the device address book and return one PhoneContact per phone
     * number (a single contact with three numbers becomes three entries).
     * Returns an empty list when READ_CONTACTS isn't granted or the cursor
     * fails to open. Names are best-effort: missing display name falls back
     * to the phone number, missing surnames just stay empty.
     */
    fun read(context: Context): List<PhoneContact> {
        val out = mutableListOf<PhoneContact>()
        val cr = context.contentResolver
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val cursor = runCatching {
            cr.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )
        }.getOrNull() ?: return emptyList()

        cursor.use { c ->
            val idIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val nameIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val seen = HashSet<String>()
            while (c.moveToNext()) {
                val rawNumber = if (numIdx >= 0) c.getString(numIdx) else null
                if (rawNumber.isNullOrBlank()) continue
                val number = normaliseNumber(rawNumber)
                if (!seen.add(number)) continue
                val display = if (nameIdx >= 0) c.getString(nameIdx) ?: "" else ""
                val (first, last) = splitName(display, number)
                val contactId = if (idIdx >= 0) c.getLong(idIdx) else 0L
                out.add(PhoneContact(number, first, last, contactId))
            }
        }
        return out
    }

    /** Strip spaces and non-digit characters except the leading "+". */
    private fun normaliseNumber(raw: String): String {
        val sb = StringBuilder()
        for ((i, ch) in raw.withIndex()) {
            if (ch == '+' && i == 0) sb.append('+')
            else if (ch.isDigit()) sb.append(ch)
        }
        return sb.toString()
    }

    /** Split a display name into (first, last). Falls back to the phone number. */
    private fun splitName(display: String, fallback: String): Pair<String, String> {
        val trimmed = display.trim()
        if (trimmed.isEmpty()) return fallback to ""
        val space = trimmed.indexOf(' ')
        return if (space < 0) trimmed to ""
        else trimmed.substring(0, space) to trimmed.substring(space + 1).trim()
    }
}
