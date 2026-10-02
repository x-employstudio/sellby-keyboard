// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Transparent, UI-less proxy Activity: launches the system contact picker (filtered to contacts
 * that have a phone number) and reports the picked name+number back via local broadcast - mirrors
 * QrisPhotoPickerActivity's proven "Activity launched from the IME, result via broadcast in
 * onStop()" pattern (an IME context can't use startActivityForResult directly).
 *
 * Targets ContactsContract.CommonDataKinds.Phone.CONTENT_URI (not the generic Contacts.CONTENT_URI
 * ActivityResultContracts.PickContact() uses) so the picker only shows contacts that actually have
 * a phone number. The contract for this intent SHOULD return a Uri pointing directly at that
 * phone-number Data row (so DISPLAY_NAME/NUMBER can be read straight off it, no extra lookup) - but
 * this isn't honored consistently across every OEM's Contacts app (confirmed via user report on a
 * non-MIUI device: picker opens and a contact is pickable, but nothing ever reaches the invoice -
 * that device's Contacts app was handing back a plain contact-level Uri instead). [resolveContact]
 * tries the direct phone-row read first, then falls back to the classic two-step "resolve contact
 * ID, then look up its phone number separately" path that works regardless of which Uri shape the
 * picker returned. The returned Uri carries a temporary read-grant from the system picker (the same
 * convention as the Photo Picker), so this does not need the READ_CONTACTS permission - the one
 * already declared in the manifest belongs to HeliBoard's unrelated stock Contacts-dictionary
 * feature and isn't touched here.
 */
class ContactPickerActivity : ComponentActivity() {

    private var resultName: String? = null
    private var resultPhone: String? = null

    private val pickContact = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        val resolved = uri?.let { resolveContact(it) }
        resultName = resolved?.first
        resultPhone = resolved?.second
        finish()
    }

    /** Returns (name, phone) or null. Tries the fast/expected path first, then a fallback that
     *  tolerates OEM Contacts apps that don't honor Phone.CONTENT_URI's Uri-shape contract. */
    private fun resolveContact(uri: Uri): Pair<String, String>? {
        try {
            contentResolver.query(
                uri,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val name = cursor.getString(0)
                    val number = cursor.getString(1)
                    if (!name.isNullOrBlank() && !number.isNullOrBlank()) return name to number
                }
            }
        } catch (_: Exception) { /* fall through to the contact-level path below */ }

        return try {
            contentResolver.query(
                uri,
                arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return null
                val contactId = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID))
                val name = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME))
                if (name.isNullOrBlank()) return null
                contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                    "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                    arrayOf(contactId),
                    null
                )?.use { phoneCursor ->
                    if (phoneCursor.moveToFirst()) {
                        val number = phoneCursor.getString(0)
                        if (!number.isNullOrBlank()) return name to number
                    }
                }
                null
            }
        } catch (_: Exception) { null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // BUG FIX: without an explicit MIME type, Android resolves ACTION_PICK on a plain
        // content:// Uri by authority alone - on this MIUI device that ambiguously matched the
        // bundled File Manager app (many file managers register as generic content pickers)
        // instead of the real Contacts app, opening a folder browser rather than the contact
        // picker. Setting the type to the exact MIME type this Uri represents narrows resolution
        // to apps that specifically declare handling phone-number contact data.
        val intent = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI).apply {
            type = ContactsContract.CommonDataKinds.Phone.CONTENT_TYPE
        }
        pickContact.launch(intent)
    }

    override fun onStop() {
        val name = resultName
        val phone = resultPhone
        if (name != null && phone != null) {
            val resultIntent = Intent(CONTACT_PICKED_ACTION).setPackage(packageName)
                .putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_PHONE, phone)
            sendBroadcast(resultIntent)
        }
        super.onStop()
    }

    companion object {
        const val CONTACT_PICKED_ACTION = "helium314.keyboard.sellby.CONTACT_PICKED"
        const val EXTRA_NAME = "contact_name"
        const val EXTRA_PHONE = "contact_phone"
    }
}
