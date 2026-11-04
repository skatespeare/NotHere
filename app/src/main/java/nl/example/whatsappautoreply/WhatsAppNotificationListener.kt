package nl.example.whatsappautoreply

import android.app.Notification
import android.app.Person
import android.app.RemoteInput
import android.content.Intent
import android.provider.ContactsContract
import android.net.Uri
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class WhatsAppNotificationListener : NotificationListenerService() {

    companion object {
        const val PREFS = "autoreply_settings"
        const val ENABLED = "enabled"
        const val EXCLUDE_GROUPS = "exclude_groups"
        const val CUSTOM_REPLY = "custom_reply"
        const val COOLDOWN_PREF = "cooldown_duration"
        const val DIAGNOSTICS_ENABLED = "diagnostics_enabled"
        const val CONTACT_EXCLUSIONS = "contact_exclusions"

        const val SERVICE_STATUS = "service_status"
        const val SERVICE_STATUS_TIME = "service_status_time"

        const val DEFAULT_REPLY =
            "I switched to Signal. Please find me there. Thank you!"

        const val DEFAULT_COOLDOWN = 30 * 60 * 1000L

        private const val PACKAGE = "com.whatsapp"
        private const val LOG_KEY = "reply_log"
        private const val GROUP_CONVERSATION_KEY =
            "android.isGroupConversation"

        private const val TAG = "WhatsAppAutoReply"

        private val lastReply = ConcurrentHashMap<String, Long>()
        private val processedMessages = ConcurrentHashMap<String, Long>()

        fun readLogs(
            prefs: android.content.SharedPreferences
        ): List<JSONObject> {
            return try {
                val a = JSONArray(prefs.getString(LOG_KEY, "[]"))
                (0 until a.length())
                    .mapNotNull { a.optJSONObject(it) }
                    .reversed()
            } catch (_: Exception) {
                emptyList()
            }
        }

        private fun log(
            p: android.content.SharedPreferences,
            who: String,
            state: String,
            detail: String,
            diagnostic: Boolean = false
        ) {
            if (
                diagnostic &&
                !p.getBoolean(DIAGNOSTICS_ENABLED, false)
            ) {
                return
            }

            try {
                val old = readLogs(p).reversed()
                val a = JSONArray()

                (old + JSONObject()
                    .put("time", System.currentTimeMillis())
                    .put("who", who)
                    .put("state", state)
                    .put("detail", detail)
                    .put("diagnostic", diagnostic)
                        ).takeLast(100).forEach {
                        a.put(it)
                    }

                p.edit().putString(LOG_KEY, a.toString()).apply()

                Log.i(TAG, "$state | $who | $detail")

            } catch (ex: Exception) {
                Log.e(TAG, "Could not save log", ex)
            }
        }

        private fun saveServiceStatus(
            p: android.content.SharedPreferences,
            connected: Boolean
        ) {
            p.edit()
                .putString(
                    SERVICE_STATUS,
                    if (connected) "CONNECTED" else "DISCONNECTED"
                )
                .putLong(SERVICE_STATUS_TIME, System.currentTimeMillis())
                .apply()
        }
    }

    override fun onCreate() {
        super.onCreate()

        Log.i(TAG, "Service onCreate")

        val p = getSharedPreferences(PREFS, MODE_PRIVATE)

        log(
            p,
            "SYSTEM",
            "Service started",
            "Notification listener service created",
            diagnostic = true
        )
    }

    override fun onListenerConnected() {
        super.onListenerConnected()

        val p = getSharedPreferences(PREFS, MODE_PRIVATE)

        saveServiceStatus(p, true)

        log(
            p,
            "SYSTEM",
            "Connected",
            "Notification listener connected to Android",
            diagnostic = true
        )
    }

    override fun onListenerDisconnected() {
        val p = getSharedPreferences(PREFS, MODE_PRIVATE)

        saveServiceStatus(p, false)

        log(
            p,
            "SYSTEM",
            "Disconnected",
            "Notification listener disconnected from Android",
            diagnostic = true
        )

        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        Log.w(TAG, "Service onDestroy")
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {

        val p = getSharedPreferences(PREFS, MODE_PRIVATE)

        log(
            p,
            sbn.packageName,
            "Received",
            "Notification received. id=${sbn.id}, key=${sbn.key}",
            diagnostic = true
        )

        if (sbn.packageName != PACKAGE) return

        val n = sbn.notification ?: return
        val e = n.extras ?: Bundle()

        val title = e.getCharSequence(Notification.EXTRA_TITLE)
            ?.toString()?.trim().orEmpty()

        val text = e.getCharSequence(Notification.EXTRA_TEXT)
            ?.toString()?.trim().orEmpty()

        log(
            p,
            title.ifBlank { "(untitled notification)" },
            "Diagnostic",
            buildNotificationDiagnostic(sbn),
            diagnostic = true
        )

        if (!p.getBoolean(ENABLED, false)) return
        if (title.isBlank() || text.isBlank()) return

        val replyMessage = p.getString(
            CUSTOM_REPLY,
            DEFAULT_REPLY
        )?.trim().orEmpty().ifBlank {
            DEFAULT_REPLY
        }

        if (text.equals(replyMessage, ignoreCase = true)) {
            log(
                p,
                title,
                "Skipped",
                "Message matches our own automatic reply",
                diagnostic = true
            )
            return
        }

        if (isOutgoing(e, text, replyMessage)) {
            log(
                p,
                title,
                "Skipped",
                "Outgoing message detected",
                diagnostic = true
            )
            return
        }

        val action = findReplyAction(n)

        if (action == null) {
            log(
                p,
                title,
                "Skipped",
                "No usable reply action found",
                diagnostic = true
            )
            return
        }

        if (
            p.getBoolean(EXCLUDE_GROUPS, true) &&
            looksLikeGroup(e)
        ) {
            log(
                p,
                title,
                "Skipped",
                "Group conversation detected",
                diagnostic = true
            )
            return
        }

        val senderUri = getLatestSenderUri(e)
        if (
            senderUri != null &&
            isContactExcluded(p, senderUri)
        ) {
            log(
                p,
                title,
                "Excluded contact",
                "Contact is on the exclusion list"
            )
            return
        }

        val inputs = action.remoteInputs ?: return
        val freeFormInputs = inputs.filter {
            it.allowFreeFormInput
        }

        if (freeFormInputs.isEmpty()) {
            log(
                p,
                title,
                "Skipped",
                "No free-form RemoteInput available",
                diagnostic = true
            )
            return
        }

        val now = System.currentTimeMillis()

        val messageSignature = getMessageSignature(sbn, e, text)

        val previousProcessed = processedMessages[messageSignature] ?: 0L

        if (now - previousProcessed < 60_000L) {
            log(
                p,
                title,
                "Skipped",
                "Duplicate notification update",
                diagnostic = true
            )
            return
        }

        val cooldown = p.getLong(
            COOLDOWN_PREF,
            DEFAULT_COOLDOWN
        )

        val conversationKey = "$PACKAGE:$title"
        val previousReply = lastReply[conversationKey] ?: 0L

        if (cooldown > 0L && now - previousReply < cooldown) {
            log(
                p,
                title,
                "Skipped",
                "Cooldown active: ${cooldown / 60000} minutes",
                diagnostic = true
            )
            return
        }

        val fill = Intent()
        val results = Bundle()

        freeFormInputs.forEach { input ->
            results.putCharSequence(input.resultKey, replyMessage)
        }

        RemoteInput.addResultsToIntent(inputs, fill, results)

        processedMessages[messageSignature] = now

        try {
            action.actionIntent.send(this, 0, fill)

            lastReply[conversationKey] = now

            if (processedMessages.size > 500) {
                processedMessages.entries.removeIf {
                    now - it.value > 60_000L
                }
            }

            log(p, title, "Sent", replyMessage)

        } catch (ex: Exception) {
            processedMessages.remove(messageSignature)

            log(
                p,
                title,
                "Failed",
                "${ex.javaClass.simpleName}: ${ex.message}"
            )
        }
    }


    private fun getLatestSenderUri(extras: Bundle): String? {
        val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        val latestMessage = messages?.lastOrNull() as? Bundle
        val sender = latestMessage?.getParcelable("sender_person") as? Person
        return sender?.uri?.takeIf { it.isNotBlank() }
    }

    private fun extractLookupKeyFromUri(uriString: String): String? {
        if (uriString.isBlank()) return null

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

    /**
     * Resolves an Android Contacts lookup URI to the underlying contact ID.
     * Lookup keys can differ for the same contact, so comparing their text
     * directly is not reliable.
     */
    private fun resolveContactId(uriString: String): Long? {
        if (uriString.isBlank()) return null

        return try {
            val lookupUri = Uri.parse(uriString)
            val contactUri = ContactsContract.Contacts.lookupContact(
                contentResolver,
                lookupUri
            ) ?: return null

            val projection = arrayOf(ContactsContract.Contacts._ID)

            contentResolver.query(
                contactUri,
                projection,
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idIndex = cursor.getColumnIndex(ContactsContract.Contacts._ID)
                    if (idIndex >= 0) cursor.getLong(idIndex) else null
                } else {
                    null
                }
            }
        } catch (ex: Exception) {
            Log.w(TAG, "Could not resolve contact URI: $uriString", ex)
            null
        }
    }

    private fun isContactExcluded(
        prefs: android.content.SharedPreferences,
        senderUri: String
    ): Boolean {
        return try {
            val contacts = JSONArray(
                prefs.getString(CONTACT_EXCLUSIONS, "[]")
            )

            val senderContactId = resolveContactId(senderUri)
            val senderLookupKey = extractLookupKeyFromUri(senderUri)
            val diagnosticEntries = mutableListOf<String>()
            var matched = false

            for (index in 0 until contacts.length()) {
                val item = contacts.optJSONObject(index) ?: continue
                val savedUri = item.optString("lookupUri")
                val savedKey = item.optString("lookupKey")

                val savedContactId = resolveContactId(savedUri)
                val savedUriKey = extractLookupKeyFromUri(savedUri)
                    ?: savedKey.takeIf { it.isNotBlank() }

                val idMatch = senderContactId != null &&
                        savedContactId != null &&
                        senderContactId == savedContactId

                // Exact key matching is retained only as a fallback when
                // Android cannot resolve one or both contact URIs.
                val keyMatch = (senderContactId == null || savedContactId == null) &&
                        senderLookupKey != null &&
                        savedUriKey == senderLookupKey

                diagnosticEntries.add(
                    "name=${item.optString("name")}, " +
                            "savedId=$savedContactId, savedKey=$savedUriKey, " +
                            "idMatch=$idMatch, keyFallback=$keyMatch"
                )

                if (idMatch || keyMatch) {
                    matched = true
                    break
                }
            }

            log(
                prefs,
                "CONTACT EXCLUSION",
                "Diagnostic",
                "senderUri=$senderUri; senderId=$senderContactId; " +
                        "senderLookupKey=$senderLookupKey; " +
                        "saved=${diagnosticEntries.joinToString(" | ")}; match=$matched",
                diagnostic = true
            )

            matched
        } catch (ex: Exception) {
            Log.e(TAG, "Could not read contact exclusion list", ex)
            false
        }
    }

    private fun findReplyAction(
        notification: Notification
    ): Notification.Action? {

        val actions = notification.actions ?: return null

        return actions.firstOrNull { action ->
            action.remoteInputs?.any {
                it.allowFreeFormInput
            } == true
        }
    }

    private fun isOutgoing(
        extras: Bundle,
        text: String,
        replyMessage: String
    ): Boolean {

        if (text.equals(replyMessage, ignoreCase = true)) {
            return true
        }

        val lower = text.lowercase()

        if (
            listOf(
                "you:",
                "jij:",
                "you sent:",
                "jij hebt verzonden:",
                "you reacted",
                "jij hebt gereageerd"
            ).any { lower.startsWith(it) }
        ) {
            return true
        }

        val selfPerson = extras.getParcelable(
            "android.messagingStyleUser"
        ) as? Person

        val selfName = selfPerson?.name?.toString()
            ?: extras.getCharSequence(
                Notification.EXTRA_SELF_DISPLAY_NAME
            )?.toString()

        val messages = extras.getParcelableArray(
            Notification.EXTRA_MESSAGES
        )

        val latestMessage = messages
            ?.lastOrNull() as? Bundle

        val sender = latestMessage?.getParcelable(
            "sender_person"
        ) as? Person

        val senderName = sender?.name?.toString()

        if (
            !selfName.isNullOrBlank() &&
            !senderName.isNullOrBlank() &&
            senderName.equals(selfName, ignoreCase = true)
        ) {
            return true
        }

        return false
    }

    private fun getMessageSignature(
        sbn: StatusBarNotification,
        extras: Bundle,
        text: String
    ): String {

        val messages = extras.getParcelableArray(
            Notification.EXTRA_MESSAGES
        )

        val latestMessage = messages
            ?.lastOrNull() as? Bundle

        val messageTime = latestMessage?.getLong("time") ?: 0L
        val messageText = latestMessage
            ?.getCharSequence("text")
            ?.toString()
            ?: text

        return "${sbn.key}|$messageTime|$messageText"
    }

    private fun looksLikeGroup(e: Bundle): Boolean {
        if (!e.containsKey(GROUP_CONVERSATION_KEY)) {
            return false
        }

        return e.getBoolean(GROUP_CONVERSATION_KEY, false)
    }


    private fun buildNotificationDiagnostic(
        sbn: StatusBarNotification
    ): String {

        val n = sbn.notification
        val e = n.extras ?: Bundle()
        val actions = n.actions ?: emptyArray()

        fun personInfo(person: Person?): String {
            if (person == null) return "null"

            return "name=${person.name}, " +
                    "key=${person.key}, " +
                    "uri=${person.uri}"
        }

        val messages = e.getParcelableArray(
            Notification.EXTRA_MESSAGES
        )

        val latestMessage = messages
            ?.lastOrNull() as? Bundle

        val sender = latestMessage?.getParcelable(
            "sender_person"
        ) as? Person

        val selfPerson = e.getParcelable(
            "android.messagingUser"
        ) as? Person

        val people = try {
            e.getParcelableArrayList<Person>(
                "android.people.list"
            )
        } catch (_: Exception) {
            null
        }

        val peopleDetails = people
            ?.joinToString("; ") { personInfo(it) }
            ?: "none"

        val actionDetails = if (actions.isEmpty()) {
            "no actions"
        } else {
            actions.mapIndexed { index, action ->

                val inputs = action.remoteInputs ?: emptyArray()

                val inputDetails = if (inputs.isEmpty()) {
                    "no RemoteInput"
                } else {
                    inputs.joinToString {
                        "key=${it.resultKey}, " +
                                "freeForm=${it.allowFreeFormInput}"
                    }
                }

                "[$index title=${action.title}, $inputDetails]"
            }.joinToString("; ")
        }

        val extraKeys = e.keySet().sorted().joinToString(",")

        return "package=${sbn.packageName}, " +
                "title=${e.getCharSequence(Notification.EXTRA_TITLE)}, " +
                "text=${e.getCharSequence(Notification.EXTRA_TEXT)}, " +
                "isGroupConversation=${e.getBoolean("android.isGroupConversation", false)}, " +
                "messageCount=${messages?.size ?: 0}, " +
                "latestSender={${personInfo(sender)}}, " +
                "selfPerson={${personInfo(selfPerson)}}, " +
                "peopleList={$peopleDetails}, " +
                "legacySender=${latestMessage?.getCharSequence("sender")}, " +
                "latestMessageKeys=${latestMessage?.keySet()?.sorted()}, " +
                "actionCount=${actions.size}, " +
                "actions={$actionDetails}, " +
                "extrasKeys={$extraKeys}"
    }

}