// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.utils.prefs

/**
 * Transparent, UI-less proxy Activity: asks for the contacts permission the very first time, launches
 * the system contact picker (filtered to contacts that have a phone number) and reports the picked
 * name+number back via local broadcast - the same proven "Activity launched from the IME, result via
 * broadcast" pattern as QrisPhotoPickerActivity (an IME context can't use startActivityForResult).
 *
 * Why it kept failing before ("the contact picker opens, but the picked contact never reaches the
 * invoice"): the manifest declared this Activity noHistory="true". A noHistory Activity is finished by
 * the system the moment another Activity covers it - and the system contact picker IS that other
 * Activity - so the result had no Activity left to be delivered to. That is also exactly what broke the
 * QRIS photo picker earlier. The manifest entry no longer has noHistory (and has its own empty
 * taskAffinity so it can't pull the companion app's task to the front), and the result is now broadcast
 * the moment it arrives instead of from onStop().
 *
 * READ_CONTACTS is requested ONCE, on the first tap of the contact button. It makes reading the picked
 * contact independent of how a given OEM's Contacts app hands out the temporary read grant (some return a
 * bare contact Uri, and the fallback in [resolveContact] that looks the number up by contact id needs the
 * permission) - the same bug-avoidance reason the permission prompt was accepted in the first place. Only the
 * ONE contact the user taps is ever read; nothing is stored beyond the invoice it lands in. Allow or deny, the
 * picker is launched afterwards: a denial is never asked again (we do not nag), and the picker's own grant is
 * usually enough to read the contact anyway.
 *
 * Targets ContactsContract.CommonDataKinds.Phone.CONTENT_URI (not the generic Contacts.CONTENT_URI
 * ActivityResultContracts.PickContact() uses) so the picker only shows contacts that actually have a
 * phone number. [resolveContact] reads the picked row directly, then falls back to the classic
 * two-step "resolve contact ID, then look up its phone number" path for Contacts apps that hand back
 * a plain contact-level Uri instead.
 */
class ContactPickerActivity : ComponentActivity() {

    private val pickContact = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val resolved = result.data?.data?.let { resolveContact(it) }
        if (resolved != null) {
            // Sent right away (not from onStop): nothing about the result depends on this Activity
            // staying around afterwards.
            sendBroadcast(
                Intent(CONTACT_PICKED_ACTION).setPackage(packageName)
                    .putExtra(EXTRA_NAME, resolved.first)
                    .putExtra(EXTRA_PHONE, resolved.second)
            )
        }
        done()
    }

    // Whatever the answer, carry on to the picker (see class comment).
    private val requestContactsPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { launchPicker() }

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
        // Only on a fresh start: after a configuration change/process recreation the result of the
        // already-running picker is delivered to the callbacks above, a second picker must not open.
        if (savedInstanceState != null) return

        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        val alreadyAsked = prefs().getBoolean(PREF_CONTACTS_PERMISSION_ASKED, false)
        if (granted || alreadyAsked) {
            launchPicker()
        } else {
            prefs().edit { putBoolean(PREF_CONTACTS_PERMISSION_ASKED, true) }
            requestContactsPermission.launch(Manifest.permission.READ_CONTACTS)
        }
    }

    private fun launchPicker() {
        // An explicit MIME type is required: without it Android resolves ACTION_PICK on a plain
        // content:// Uri by authority alone, and on MIUI that ambiguously matched the bundled File
        // Manager (many file managers register as generic content pickers) instead of the real
        // Contacts app, opening a folder browser rather than the contact picker.
        // (setType() after a URI in the constructor drops the URI anyway - this is the same intent, written the
        // way the platform documents the contact picker: ACTION_PICK + a Phone MIME type, no data URI.)
        val intent = Intent(Intent.ACTION_PICK).apply {
            type = ContactsContract.CommonDataKinds.Phone.CONTENT_TYPE
        }
        try {
            pickContact.launch(intent)
        } catch (_: Exception) {
            done() // no Contacts app that can pick - nothing to report
        }
    }

    /** Picked, cancelled or failed: close this proxy and let the keyboard come back with the Invoice panel
     *  open (KeyboardSwitcher.endSellbyHelper) - after a pick the keyboard otherwise stayed hidden and the
     *  panel was gone, so the user had to open the panel again. */
    private fun done() {
        finish()
        KeyboardSwitcher.getInstance().endSellbyHelper()
    }

    companion object {
        const val CONTACT_PICKED_ACTION = "helium314.keyboard.sellby.CONTACT_PICKED"
        const val EXTRA_NAME = "contact_name"
        const val EXTRA_PHONE = "contact_phone"
        private const val PREF_CONTACTS_PERMISSION_ASKED = "sellby_contacts_permission_asked"
    }
}
