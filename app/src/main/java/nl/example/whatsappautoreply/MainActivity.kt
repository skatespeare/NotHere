package nl.example.whatsappautoreply

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.provider.ContactsContract
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject

class MainActivity : Activity() {
    companion object {
        private const val REQUEST_PICK_CONTACT = 1001
        private const val REQUEST_READ_CONTACTS = 1002
        private const val CONTACT_EXCLUSIONS_KEY = WhatsAppNotificationListener.CONTACT_EXCLUSIONS
    }

    private lateinit var logArea: LinearLayout
    private lateinit var status: TextView
    private lateinit var warning: TextView
    private lateinit var replyInput: EditText
    private lateinit var cooldownSpinner: Spinner
    private lateinit var excludedContactsArea: LinearLayout

    private val prefs by lazy {
        getSharedPreferences(
            WhatsAppNotificationListener.PREFS,
            MODE_PRIVATE
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 36, 32, 24)
        }

        page.addView(TextView(this).apply {
            text = "NotHere"
            textSize = 26f
        })

        page.addView(TextView(this).apply {
            text = "\nAutomatic reply message"
            textSize = 18f
        })

        page.addView(TextView(this).apply {
            text = "Enter the message that will automatically be sent."
            textSize = 14f
        })

        replyInput = EditText(this).apply {
            setText(
                prefs.getString(
                    WhatsAppNotificationListener.CUSTOM_REPLY,
                    WhatsAppNotificationListener.DEFAULT_REPLY
                )
            )
            hint = "Enter your automatic reply..."
            minLines = 3
            maxLines = 6
            gravity = Gravity.TOP or Gravity.START
        }

        page.addView(
            replyInput,
            LinearLayout.LayoutParams(-1, -2)
        )

        page.addView(Button(this).apply {
            text = "Save automatic reply"
            setOnClickListener {
                val message = replyInput.text.toString().trim()

                if (message.isBlank()) {
                    replyInput.error = "Please enter a message."
                    return@setOnClickListener
                }

                prefs.edit()
                    .putString(
                        WhatsAppNotificationListener.CUSTOM_REPLY,
                        message
                    )
                    .apply()

                Toast.makeText(
                    this@MainActivity,
                    "Automatic reply saved.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        })

        page.addView(Button(this).apply {
            text = "Restore default message"
            setOnClickListener {
                replyInput.setText(
                    WhatsAppNotificationListener.DEFAULT_REPLY
                )

                prefs.edit()
                    .remove(WhatsAppNotificationListener.CUSTOM_REPLY)
                    .apply()

                Toast.makeText(
                    this@MainActivity,
                    "Default message restored.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        })

        page.addView(TextView(this).apply {
            text = "\nReply frequency"
            textSize = 18f
        })

        page.addView(TextView(this).apply {
            text = "Choose how often an automatic reply can be sent to the same contact."
            textSize = 14f
        })

        cooldownSpinner = Spinner(this)

        val cooldownOptions = listOf(
            "Every message (unlimited)",
            "Once every 30 minutes",
            "Once every 24 hours"
        )

        cooldownSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            cooldownOptions
        ).also {
            it.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item
            )
        }

        val savedCooldown = prefs.getLong(
            WhatsAppNotificationListener.COOLDOWN_PREF,
            WhatsAppNotificationListener.DEFAULT_COOLDOWN
        )

        cooldownSpinner.setSelection(
            when (savedCooldown) {
                0L -> 0
                24 * 60 * 60 * 1000L -> 2
                else -> 1
            }
        )

        cooldownSpinner.setOnItemSelectedListener(
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: android.view.View?,
                    position: Int,
                    id: Long
                ) {
                    val value = when (position) {
                        0 -> 0L
                        2 -> 24 * 60 * 60 * 1000L
                        else -> 30 * 60 * 1000L
                    }

                    prefs.edit()
                        .putLong(
                            WhatsAppNotificationListener.COOLDOWN_PREF,
                            value
                        )
                        .apply()
                }

                override fun onNothingSelected(
                    parent: android.widget.AdapterView<*>?
                ) {}
            }
        )

        page.addView(cooldownSpinner)

        page.addView(TextView(this).apply {
            text = "\nWhatsApp settings"
            textSize = 18f
        })

        warning = TextView(this).apply {
            textSize = 14f
            setPadding(16, 16, 16, 16)
            setBackgroundColor(0xFFFFF1CC.toInt())
        }

        page.addView(warning)

        page.addView(Switch(this).apply {
            text = "Enable automatic replies"
            isChecked = prefs.getBoolean(
                WhatsAppNotificationListener.ENABLED,
                false
            )

            setOnCheckedChangeListener { _, checked ->
                prefs.edit()
                    .putBoolean(
                        WhatsAppNotificationListener.ENABLED,
                        checked
                    )
                    .apply()

                updateStatus()
            }
        })

        page.addView(Switch(this).apply {
            text = "Exclude group chats (recommended)"
            isChecked = prefs.getBoolean(
                WhatsAppNotificationListener.EXCLUDE_GROUPS,
                true
            )

            setOnCheckedChangeListener { _, checked ->
                prefs.edit()
                    .putBoolean(
                        WhatsAppNotificationListener.EXCLUDE_GROUPS,
                        checked
                    )
                    .apply()
            }
        })

        page.addView(TextView(this).apply {
            text = "\nContact exclusions"
            textSize = 18f
        })

        page.addView(TextView(this).apply {
            text = "Selected individual contacts will not receive automatic replies. Contact access lets the app refresh saved references after contact changes. Group exclusion is controlled separately above."
            textSize = 14f
        })

        excludedContactsArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        page.addView(excludedContactsArea)

        page.addView(Button(this).apply {
            text = "Add contact to exclusion list"
            setOnClickListener { openContactPicker() }
        })

        page.addView(Button(this).apply {
            text = "Set up notification access"
            setOnClickListener {
                startActivity(
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                )
            }
        })

        status = TextView(this).apply {
            textSize = 14f
            setPadding(0, 12, 0, 12)
        }

        page.addView(status)

        page.addView(TextView(this).apply {
            text = "\nDeveloper settings"
            textSize = 18f
        })

        page.addView(Switch(this).apply {
            text = "Show diagnostic information"
            isChecked = prefs.getBoolean(
                WhatsAppNotificationListener.DIAGNOSTICS_ENABLED,
                false
            )

            setOnCheckedChangeListener { _, checked ->
                prefs.edit()
                    .putBoolean(
                        WhatsAppNotificationListener.DIAGNOSTICS_ENABLED,
                        checked
                    )
                    .apply()

                refreshLog()

                Toast.makeText(
                    this@MainActivity,
                    if (checked) {
                        "Diagnostic logging enabled."
                    } else {
                        "Diagnostic logging disabled."
                    },
                    Toast.LENGTH_SHORT
                ).show()
            }
        })

        page.addView(TextView(this).apply {
            text = "When enabled, technical notification details, service events and skipped messages are recorded."
            textSize = 13f
        })

        page.addView(TextView(this).apply {
            text = "\nActivity log (last 100 events)"
            textSize = 18f
        })

        logArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        page.addView(
            ScrollView(this).apply {
                addView(logArea)
            },
            LinearLayout.LayoutParams(-1, 0, 1f)
        )

        page.addView(Button(this).apply {
            text = "Refresh activity log"
            setOnClickListener { refreshLog() }
        })

        page.addView(Button(this).apply {
            text = "Copy latest event"
            setOnClickListener { copyActivityLog(true) }
        })

        page.addView(Button(this).apply {
            text = "Copy full activity log"
            setOnClickListener { copyActivityLog(false) }
        })

        page.addView(Button(this).apply {
            text = "Clear activity log"
            setOnClickListener {
                prefs.edit().remove("reply_log").apply()
                refreshLog()
            }
        })

        setContentView(
            ScrollView(this).apply {
                addView(page)
            }
        )

        updateStatus()
        refreshLog()
        ensureContactsPermission()
        refreshExcludedContacts()
    }

    override fun onResume() {
        super.onResume()

        if (::status.isInitialized) {
            updateStatus()
            refreshLog()
            refreshExcludedContacts()
        }
    }


    private fun hasContactsPermission(): Boolean {
        return android.os.Build.VERSION.SDK_INT < 23 ||
                checkSelfPermission(Manifest.permission.READ_CONTACTS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun ensureContactsPermission() {
        if (!hasContactsPermission()) {
            requestPermissions(
                arrayOf(Manifest.permission.READ_CONTACTS),
                REQUEST_READ_CONTACTS
            )
        } else {
            refreshExcludedContacts()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQUEST_READ_CONTACTS) {
            if (grantResults.isNotEmpty() &&
                grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                refreshExcludedContacts()
                Toast.makeText(
                    this,
                    "Contact access granted. Excluded contacts will be refreshed.",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(
                    this,
                    "Contact access was not granted. Saved exclusions remain, but contact references cannot be refreshed.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun openContactPicker() {
        if (!hasContactsPermission()) {
            ensureContactsPermission()
            return
        }
        val intent = Intent(
            Intent.ACTION_PICK,
            ContactsContract.Contacts.CONTENT_URI
        )
        startActivityForResult(intent, REQUEST_PICK_CONTACT)
    }

    @Deprecated("Use of startActivityForResult is retained for broad Android compatibility.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode != REQUEST_PICK_CONTACT || resultCode != RESULT_OK) return

        val contactUri = data?.data ?: return
        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.DISPLAY_NAME,
            ContactsContract.Contacts.LOOKUP_KEY
        )

        try {
            contentResolver.query(
                contactUri,
                projection,
                null,
                null,
                null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) {
                    Toast.makeText(this, "Could not read the selected contact.", Toast.LENGTH_SHORT).show()
                    return
                }

                val idIndex = cursor.getColumnIndex(ContactsContract.Contacts._ID)
                val nameIndex = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                val lookupIndex = cursor.getColumnIndex(ContactsContract.Contacts.LOOKUP_KEY)

                if (idIndex < 0 || nameIndex < 0 || lookupIndex < 0) {
                    Toast.makeText(this, "Contact information is unavailable.", Toast.LENGTH_SHORT).show()
                    return
                }

                val contactId = cursor.getLong(idIndex)
                val name = cursor.getString(nameIndex)?.trim().orEmpty()
                val lookupKey = cursor.getString(lookupIndex).orEmpty()

                if (lookupKey.isBlank()) {
                    Toast.makeText(this, "This contact has no usable lookup key.", Toast.LENGTH_SHORT).show()
                    return
                }

                val stableUri = ContactsContract.Contacts.getLookupUri(
                    contactId,
                    lookupKey
                )?.toString().orEmpty()

                if (stableUri.isBlank()) {
                    Toast.makeText(this, "Could not create a stable contact reference.", Toast.LENGTH_SHORT).show()
                    return
                }

                val canonicalLookupKey = lookupKeyFromUri(stableUri) ?: lookupKey
                addExcludedContact(name.ifBlank { "Unnamed contact" }, canonicalLookupKey, stableUri)
            }
        } catch (ex: Exception) {
            Toast.makeText(
                this,
                "Could not add contact: ${ex.javaClass.simpleName}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun readExcludedContacts(): org.json.JSONArray {
        return try {
            org.json.JSONArray(
                prefs.getString(CONTACT_EXCLUSIONS_KEY, "[]")
            )
        } catch (_: Exception) {
            org.json.JSONArray()
        }
    }

    private fun lookupKeyFromUri(uriString: String): String? {
        return try {
            val segments = Uri.parse(uriString).pathSegments
            val lookupIndex = segments.indexOf("lookup")
            if (lookupIndex >= 0 && lookupIndex + 1 < segments.size) {
                segments[lookupIndex + 1].takeIf { it.isNotBlank() }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun addExcludedContact(name: String, lookupKey: String, lookupUri: String) {
        val contacts = readExcludedContacts()

        for (i in 0 until contacts.length()) {
            val existing = contacts.optJSONObject(i) ?: continue
            if (existing.optString("lookupKey") == lookupKey) {
                Toast.makeText(this, "This contact is already excluded.", Toast.LENGTH_SHORT).show()
                refreshExcludedContacts()
                return
            }
        }

        contacts.put(
            JSONObject()
                .put("name", name)
                .put("lookupKey", lookupKey)
                .put("lookupUri", lookupUri)
        )

        prefs.edit()
            .putString(CONTACT_EXCLUSIONS_KEY, contacts.toString())
            .apply()

        refreshExcludedContacts()
        Toast.makeText(this, "$name added to exclusions.", Toast.LENGTH_SHORT).show()
    }

    private fun removeExcludedContact(lookupKey: String) {
        val oldContacts = readExcludedContacts()
        val updated = org.json.JSONArray()

        for (i in 0 until oldContacts.length()) {
            val item = oldContacts.optJSONObject(i) ?: continue
            if (item.optString("lookupKey") != lookupKey) {
                updated.put(item)
            }
        }

        prefs.edit()
            .putString(CONTACT_EXCLUSIONS_KEY, updated.toString())
            .apply()

        refreshExcludedContacts()
    }

    private fun refreshContactReferences(): org.json.JSONArray {
        val stored = readExcludedContacts()
        val refreshed = org.json.JSONArray()
        var changed = false

        for (i in 0 until stored.length()) {
            val item = stored.optJSONObject(i) ?: continue
            val oldName = item.optString("name", "Unknown contact")
            val oldLookupKey = item.optString("lookupKey")
            val oldLookupUri = item.optString("lookupUri")

            var newItem = JSONObject(item.toString())

            if (hasContactsPermission() && oldLookupUri.isNotBlank()) {
                try {
                    val oldUri = Uri.parse(oldLookupUri)
                    val currentContactUri =
                        ContactsContract.Contacts.lookupContact(contentResolver, oldUri)

                    if (currentContactUri != null) {
                        val projection = arrayOf(
                            ContactsContract.Contacts._ID,
                            ContactsContract.Contacts.DISPLAY_NAME,
                            ContactsContract.Contacts.LOOKUP_KEY
                        )

                        contentResolver.query(
                            currentContactUri,
                            projection,
                            null,
                            null,
                            null
                        )?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val idIndex = cursor.getColumnIndex(ContactsContract.Contacts._ID)
                                val nameIndex = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                                val keyIndex = cursor.getColumnIndex(ContactsContract.Contacts.LOOKUP_KEY)

                                if (idIndex >= 0 && nameIndex >= 0 && keyIndex >= 0) {
                                    val id = cursor.getLong(idIndex)
                                    val name = cursor.getString(nameIndex)?.trim().orEmpty()
                                    val key = cursor.getString(keyIndex).orEmpty()
                                    val newUri = ContactsContract.Contacts.getLookupUri(id, key)
                                        ?.toString().orEmpty()
                                    val canonicalKey = lookupKeyFromUri(newUri) ?: key

                                    if (canonicalKey.isNotBlank() && newUri.isNotBlank()) {
                                        newItem = JSONObject()
                                            .put("name", name.ifBlank { oldName })
                                            .put("lookupKey", canonicalKey)
                                            .put("lookupUri", newUri)

                                        if (name != oldName ||
                                            canonicalKey != oldLookupKey ||
                                            newUri != oldLookupUri
                                        ) {
                                            changed = true
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (ex: Exception) {
                    // Keep the saved reference if Android cannot resolve it.
                    android.util.Log.w(
                        "WhatsAppAutoReply",
                        "Could not refresh excluded contact: $oldName",
                        ex
                    )
                }
            }

            refreshed.put(newItem)
        }

        if (changed) {
            prefs.edit()
                .putString(CONTACT_EXCLUSIONS_KEY, refreshed.toString())
                .apply()
        }

        return refreshed
    }

    private fun refreshExcludedContacts() {
        if (!::excludedContactsArea.isInitialized) return

        excludedContactsArea.removeAllViews()
        val contacts = refreshContactReferences()

        if (contacts.length() == 0) {
            excludedContactsArea.addView(TextView(this).apply {
                text = "No excluded contacts."
                textSize = 14f
                setPadding(0, 8, 0, 8)
            })
            return
        }

        for (i in 0 until contacts.length()) {
            val item = contacts.optJSONObject(i) ?: continue
            val name = item.optString("name", "Unknown contact")
            val lookupKey = item.optString("lookupKey")
            if (lookupKey.isBlank()) continue

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 4, 0, 4)
            }

            row.addView(
                TextView(this).apply {
                    text = name
                    textSize = 15f
                },
                LinearLayout.LayoutParams(0, -2, 1f)
            )

            row.addView(Button(this).apply {
                text = "Remove"
                setOnClickListener { removeExcludedContact(lookupKey) }
            })

            excludedContactsArea.addView(row)
        }
    }

    private fun hasNotificationListenerAccess(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        ) ?: return false

        val ours = ComponentName(
            this,
            WhatsAppNotificationListener::class.java
        )

        return enabled.split(":").any {
            it.equals(ours.flattenToString(), ignoreCase = true)
        }
    }

    private fun updateStatus() {
        val connected = prefs.getString(
            WhatsAppNotificationListener.SERVICE_STATUS,
            "UNKNOWN"
        )

        val statusTime = prefs.getLong(
            WhatsAppNotificationListener.SERVICE_STATUS_TIME,
            0L
        )

        val formatter = SimpleDateFormat(
            "MMM dd, HH:mm:ss",
            Locale.getDefault()
        )

        val serviceDescription = when (connected) {
            "CONNECTED" -> "Connected"
            "DISCONNECTED" -> "Disconnected"
            else -> "Unknown (not yet confirmed)"
        }

        status.text = "Automatic replies: " +
                if (prefs.getBoolean(
                        WhatsAppNotificationListener.ENABLED,
                        false
                    )) "ON" else "OFF"

        status.append(
            "\nNotification access: " +
                    if (hasNotificationListenerAccess()) {
                        "GRANTED"
                    } else {
                        "NOT GRANTED"
                    }
        )

        status.append("\nLast service event: $serviceDescription")

        if (statusTime > 0L) {
            status.append(
                "\nLast status update: ${formatter.format(Date(statusTime))}"
            )
        }

        warning.text = if (!hasNotificationListenerAccess()) {
            "ACTION REQUIRED: Grant notification access. Without this permission, the app cannot detect WhatsApp messages."
        } else if (connected == "DISCONNECTED") {
            "WARNING: Android last reported that the notification service disconnected. Recheck notification access."
        } else if (connected == "UNKNOWN") {
            "The service connection has not yet been confirmed. Open notification settings if automatic replies do not work."
        } else {
            "IMPORTANT: Keep WhatsApp notifications enabled. You may turn off sounds and vibration, but disabling notifications will usually prevent message detection."
        }
    }

    private fun isDiagnosticEntry(item: JSONObject): Boolean {
        val state = item.optString("state")

        // Support older log entries that were created before
        // the diagnostic property was introduced.
        if (item.has("diagnostic")) {
            return item.optBoolean("diagnostic", false)
        }

        return state in setOf(
            "Received",
            "Diagnostic",
            "Connected",
            "Disconnected",
            "Service started",
            "Skipped"
        )
    }

    private fun visibleLogEntries(): List<JSONObject> {
        val entries = WhatsAppNotificationListener.readLogs(prefs)

        return if (
            prefs.getBoolean(
                WhatsAppNotificationListener.DIAGNOSTICS_ENABLED,
                false
            )
        ) {
            entries
        } else {
            entries.filterNot { isDiagnosticEntry(it) }
        }
    }

    private fun refreshLog() {
        if (!::logArea.isInitialized) return

        logArea.removeAllViews()

        val entries = visibleLogEntries()

        if (entries.isEmpty()) {
            logArea.addView(TextView(this).apply {
                text = "No activity yet."
                textSize = 14f
            })
            return
        }

        val formatter = SimpleDateFormat(
            "MMM dd, HH:mm:ss",
            Locale.getDefault()
        )

        entries.forEach { item ->
            logArea.addView(TextView(this).apply {
                text = "${formatter.format(Date(item.optLong("time")))} · " +
                        "${item.optString("state")}\n" +
                        "${item.optString("who")}: " +
                        item.optString("detail")

                textSize = 13f
                setPadding(0, 8, 0, 8)
            })
        }
    }

    private fun copyActivityLog(latestOnly: Boolean) {
        val entries = visibleLogEntries()

        if (entries.isEmpty()) {
            Toast.makeText(
                this,
                "No activity to copy.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val selected = if (latestOnly) entries.take(1) else entries

        val formatter = SimpleDateFormat(
            "MMM dd, HH:mm:ss",
            Locale.getDefault()
        )

        val content = selected.joinToString("\n\n") { item ->
            "${formatter.format(Date(item.optLong("time")))} · " +
                    "${item.optString("state")}\n" +
                    "${item.optString("who")}: " +
                    item.optString("detail")
        }

        val clipboard = getSystemService(CLIPBOARD_SERVICE)
                as ClipboardManager

        clipboard.setPrimaryClip(
            ClipData.newPlainText("AutoReply Activity Log", content)
        )

        Toast.makeText(
            this,
            if (latestOnly) "Latest event copied." else "Full log copied.",
            Toast.LENGTH_SHORT
        ).show()
    }
}
