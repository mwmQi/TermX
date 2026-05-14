package com.termx.app.api

import android.annotation.SuppressLint
import android.content.ContentProviderOperation
import android.content.Context
import android.provider.ContactsContract
import android.util.Log

/**
 * Contact API for TermX. List, search, add, and delete contacts.
 *
 * Usage: am broadcast -a com.termx.app.api.CONTACT_LIST --ei limit 50
 *        am broadcast -a com.termx.app.api.CONTACT_SEARCH --es query "John"
 *        am broadcast -a com.termx.app.api.CONTACT_ADD --es name "Jane" --es phone "+1234"
 *        am broadcast -a com.termx.app.api.CONTACT_DELETE --el id 42
 * Requires: READ_CONTACTS, WRITE_CONTACTS permissions
 */
object ContactApi {

    private const val TAG = "ContactApi"

    data class ContactInfo(val id: Long, val name: String, val phones: List<String>, val emails: List<String>) {
        fun toFormattedString() = buildString {
            appendLine("ID: $id | Name: $name")
            if (phones.isNotEmpty()) appendLine("Phone: ${phones.joinToString(", ")}")
            if (emails.isNotEmpty()) appendLine("Email: ${emails.joinToString(", ")}")
        }
    }

    /** List all contacts. */
    @SuppressLint("MissingPermission")
    fun listContacts(context: Context, limit: Int = 50): String = try {
        val contacts = mutableListOf<ContactInfo>()
        context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME),
            null, null, "${ContactsContract.Contacts.DISPLAY_NAME} ASC"
        )?.use { cur ->
            var count = 0
            while (cur.moveToNext() && count < limit) {
                val id = cur.getLong(0)
                val name = cur.getString(1)
                contacts.add(ContactInfo(id, name, getPhones(context.contentResolver, id), getEmails(context.contentResolver, id)))
                count++
            }
        }
        if (contacts.isEmpty()) "No contacts found"
        else "=== Contacts (${contacts.size}) ===\n" + contacts.joinToString("\n") { it.toFormattedString() }
    } catch (e: SecurityException) { "Error: READ_CONTACTS permission required" }
    catch (e: Exception) { Log.e(TAG, "List contacts failed", e); "Error: ${e.message}" }

    /** Get contact by ID. */
    @SuppressLint("MissingPermission")
    fun getContactById(context: Context, id: Long): String = try {
        context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME),
            "${ContactsContract.Contacts._ID} = ?", arrayOf(id.toString()), null
        )?.use { cur ->
            if (cur.moveToFirst()) {
                val name = cur.getString(1)
                ContactInfo(id, name, getPhones(context.contentResolver, id), getEmails(context.contentResolver, id)).toFormattedString()
            } else "Contact $id not found"
        } ?: "Contact $id not found"
    } catch (e: SecurityException) { "Error: READ_CONTACTS permission required" }
    catch (e: Exception) { "Error: ${e.message}" }

    /** Search contacts by name or phone. */
    @SuppressLint("MissingPermission")
    fun searchContacts(context: Context, query: String): String { return try {
        if (query.isBlank()) return "Error: Search query required"
        val contacts = mutableListOf<ContactInfo>()
        context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME),
            "${ContactsContract.Contacts.DISPLAY_NAME} LIKE ?", arrayOf("%$query%"),
            "${ContactsContract.Contacts.DISPLAY_NAME} ASC"
        )?.use { cur ->
            while (cur.moveToNext()) {
                val id = cur.getLong(0); val name = cur.getString(1)
                contacts.add(ContactInfo(id, name, getPhones(context.contentResolver, id), getEmails(context.contentResolver, id)))
            }
        }
        if (contacts.isEmpty()) "No contacts matching '$query'"
        else "=== Results for '$query' (${contacts.size}) ===\n" + contacts.joinToString("\n") { it.toFormattedString() }
    } catch (e: SecurityException) { "Error: READ_CONTACTS permission required" }
    catch (e: Exception) { "Error: ${e.message}" } }

    /** Add a new contact with name, phone, and optional email. */
    fun addContact(context: Context, name: String, phone: String, email: String? = null): String { return try {
        if (name.isBlank()) return "Error: Contact name required"
        val ops = arrayListOf<ContentProviderOperation>()
        ops.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
            .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
            .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null).build())
        ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
            .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name).build())
        if (phone.isNotBlank()) ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
            .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone)
            .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE).build())
        if (!email.isNullOrBlank()) ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
            .withValue(ContactsContract.CommonDataKinds.Email.DATA, email)
            .withValue(ContactsContract.CommonDataKinds.Email.TYPE, ContactsContract.CommonDataKinds.Email.TYPE_HOME).build())
        context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
        Log.i(TAG, "Contact added: $name"); "Contact added: $name"
    } catch (e: SecurityException) { "Error: WRITE_CONTACTS permission required" }
    catch (e: Exception) { Log.e(TAG, "Add contact failed", e); "Error: ${e.message}" } }

    /** Delete contact by ID. */
    @SuppressLint("MissingPermission")
    fun deleteContact(context: Context, id: Long): String = try {
        val uri = ContactsContract.Contacts.getLookupUri(id, "")
        val deleted = context.contentResolver.delete(uri, null, null)
        if (deleted > 0) { Log.i(TAG, "Contact $id deleted"); "Contact $id deleted" } else "Contact $id not found"
    } catch (e: SecurityException) { "Error: WRITE_CONTACTS permission required" }
    catch (e: Exception) { "Error: ${e.message}" }

    private fun getPhones(resolver: android.content.ContentResolver, id: Long): List<String> {
        val phones = mutableListOf<String>()
        resolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, null,
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?", arrayOf(id.toString()), null
        )?.use { c -> while (c.moveToNext()) { val i = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER); if (i >= 0) phones.add(c.getString(i)) } }
        return phones
    }

    private fun getEmails(resolver: android.content.ContentResolver, id: Long): List<String> {
        val emails = mutableListOf<String>()
        resolver.query(ContactsContract.CommonDataKinds.Email.CONTENT_URI, null,
            "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} = ?", arrayOf(id.toString()), null
        )?.use { c -> while (c.moveToNext()) { val i = c.getColumnIndex(ContactsContract.CommonDataKinds.Email.DATA); if (i >= 0) emails.add(c.getString(i)) } }
        return emails
    }
}
