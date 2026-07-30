package app.momoding.core.contacts

import android.content.Context
import app.momoding.core.capabilities.AndroidCapabilityId
import app.momoding.core.capabilities.AndroidCapabilityRegistry
import app.momoding.core.capabilities.CapabilityAvailability
import app.momoding.core.runtime.local.ANDROID_TOOL_TERMINAL_TURN_CLEANUP_REASON
import app.momoding.core.runtime.local.PiNativeAndroidToolResult
import app.momoding.core.runtime.local.PiNativeToolRequest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

interface PhoneLocalContactsToolHandler {
    fun handles(toolName: String): Boolean
    fun isPersonalDataRead(request: PiNativeToolRequest): Boolean
    fun isMutation(request: PiNativeToolRequest): Boolean
    fun mutationRequestDigest(request: PiNativeToolRequest): String
    suspend fun isReadCapabilityReady(): Boolean
    suspend fun prepareMutation(
        taskId: String,
        request: PiNativeToolRequest,
    ): ContactsMutationPreparation
    suspend fun executeMutation(
        taskId: String,
        request: PiNativeToolRequest,
        plan: ContactsMutationPlan,
        onProviderDispatch: suspend () -> Unit,
    ): PiNativeAndroidToolResult
    suspend fun execute(taskId: String, request: PiNativeToolRequest): PiNativeAndroidToolResult
    fun stopTask(taskId: String, reason: String) = Unit
}

class PhoneLocalContactsToolExecutor(
    private val gateway: ContactsGateway? = null,
    private val capabilityRegistry: AndroidCapabilityRegistry? = null,
    private val handles: ContactsHandleRegistry = ContactsHandleRegistry(),
    private val now: () -> Instant = Instant::now,
    private val timeoutMillis: Long = REQUEST_TIMEOUT_MILLIS,
) : PhoneLocalContactsToolHandler {
    override fun handles(toolName: String): Boolean = toolName == TOOL_NAME

    override fun isPersonalDataRead(request: PiNativeToolRequest): Boolean =
        try {
            !ContactsToolRequestParser.parse(request.arguments).action.isMutation
        } catch (_: ContactsToolArgumentsException) {
            false
        }

    override fun isMutation(request: PiNativeToolRequest): Boolean =
        try {
            ContactsToolRequestParser.parse(request.arguments).action.isMutation
        } catch (_: ContactsToolArgumentsException) {
            false
        }

    override fun mutationRequestDigest(request: PiNativeToolRequest): String =
        requestDigest(request.arguments)

    override suspend fun isReadCapabilityReady(): Boolean {
        val registry = capabilityRegistry ?: return false
        return registry.refreshNow()
            .first { it.id == AndroidCapabilityId.CONTACTS }
            .availability in setOf(
                CapabilityAvailability.PARTIAL,
                CapabilityAvailability.READY,
            )
    }

    override suspend fun prepareMutation(
        taskId: String,
        request: PiNativeToolRequest,
    ): ContactsMutationPreparation {
        if (taskId.isBlank() || !handles(request.toolName)) {
            return ContactsMutationPreparation.Failed(
                failed(null, "INVALID_ARGUMENTS", "Contacts arguments are invalid.", false),
            )
        }
        val parsed = try {
            ContactsToolRequestParser.parse(request.arguments)
        } catch (_: ContactsToolArgumentsException) {
            return ContactsMutationPreparation.Failed(
                failed(null, "INVALID_ARGUMENTS", "Contacts arguments are invalid.", true),
            )
        }
        if (!parsed.action.isMutation) {
            return ContactsMutationPreparation.Failed(
                failed(
                    parsed.action.wireValue,
                    "INVALID_ARGUMENTS",
                    "Contacts mutation arguments are invalid.",
                    false,
                ),
            )
        }
        val localGateway = gateway ?: return ContactsMutationPreparation.Failed(
            failed(
                parsed.action.wireValue,
                "PROVIDER_UNAVAILABLE",
                "Android Contacts Provider is not enabled in this build.",
                false,
            ),
        )
        if (!writeCapabilityReady()) {
            return ContactsMutationPreparation.Failed(
                capabilityNotReady(
                    parsed.action.wireValue,
                    "Contacts write access is not enabled.",
                    "write",
                ),
            )
        }
        return try {
            ContactsMutationPreparation.Ready(
                when (parsed) {
                    is ContactsToolRequest.CreateContact ->
                        prepareCreate(taskId, request, parsed)
                    is ContactsToolRequest.UpdateContact ->
                        prepareUpdate(taskId, request, parsed, localGateway)
                    is ContactsToolRequest.DeleteContact ->
                        prepareDelete(taskId, request, parsed, localGateway)
                    else -> error("Read request passed Contacts mutation boundary")
                },
            )
        } catch (_: StaleContactHandle) {
            ContactsMutationPreparation.Failed(
                failed(
                    parsed.action.wireValue,
                    "STALE_HANDLE",
                    "The Contacts handle is no longer valid. Search live contacts again.",
                    true,
                ),
            )
        } catch (_: ContactNotFound) {
            ContactsMutationPreparation.Failed(
                failed(
                    parsed.action.wireValue,
                    "NOT_FOUND",
                    "The contact no longer exists.",
                    false,
                ),
            )
        } catch (_: ContactReadOnlyTarget) {
            ContactsMutationPreparation.Failed(
                failed(
                    parsed.action.wireValue,
                    "READ_ONLY",
                    "The selected contact has no writable RawContact.",
                    false,
                ),
            )
        } catch (_: ContactAmbiguousTarget) {
            ContactsMutationPreparation.Failed(
                failed(
                    parsed.action.wireValue,
                    "AMBIGUOUS_TARGET",
                    "The contact has multiple account records. Choose a contact with one writable target.",
                    false,
                ),
            )
        } catch (_: SecurityException) {
            handles.clearTask(taskId)
            ContactsMutationPreparation.Failed(
                capabilityNotReady(
                    parsed.action.wireValue,
                    "Contacts write access is no longer available.",
                    "write",
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ContactsMutationPreparation.Failed(
                failed(
                    parsed.action.wireValue,
                    "PROVIDER_UNAVAILABLE",
                    "Android Contacts Provider is temporarily unavailable.",
                    true,
                ),
            )
        }
    }

    override suspend fun executeMutation(
        taskId: String,
        request: PiNativeToolRequest,
        plan: ContactsMutationPlan,
        onProviderDispatch: suspend () -> Unit,
    ): PiNativeAndroidToolResult {
        if (
            taskId != plan.taskId ||
            request.toolCallId != plan.piToolCallId ||
            requestDigest(request.arguments) != plan.requestDigest
        ) {
            return failed(
                plan.action.wireValue,
                "CONFLICT",
                "The Contacts mutation no longer matches its approved plan.",
                false,
            )
        }
        val localGateway = gateway ?: return failed(
            plan.action.wireValue,
            "PROVIDER_UNAVAILABLE",
            "Android Contacts Provider is not enabled in this build.",
            false,
        )
        if (!writeCapabilityReady()) {
            return capabilityNotReady(
                plan.action.wireValue,
                "Contacts write access is not enabled.",
                "write",
            )
        }
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        var providerCallIssued = false
        return try {
            verifyPrecondition(plan, localGateway)?.let { return it }
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                onProviderDispatch()
                providerCallIssued = true
            }
            withTimeout(timeoutMillis) {
                when (plan.action) {
                    ContactsToolAction.CREATE_CONTACT -> {
                        val rawContactId = localGateway.createContact(requireNotNull(plan.write))
                        val raw = localGateway.getRawContact(rawContactId)
                            ?: return@withTimeout verificationFailed(plan)
                        val contact = localGateway.getContactByRawContact(rawContactId)
                            ?: return@withTimeout verificationFailed(plan)
                        if (!matches(requireNotNull(plan.write), raw)) {
                            return@withTimeout verificationFailed(plan)
                        }
                        mutationSucceeded(
                            plan,
                            buildJsonObject {
                                put("contact", contactDetails(taskId, contact))
                            },
                        )
                    }
                    ContactsToolAction.UPDATE_CONTACT -> {
                        val raw = requireNotNull(plan.rawContact)
                        if (!localGateway.updateContact(
                                raw.rawContactId,
                                raw.version,
                                requireNotNull(plan.write),
                                plan.fields,
                            )
                        ) {
                            return@withTimeout conflict(plan)
                        }
                        val observedRaw = localGateway.getRawContact(raw.rawContactId)
                            ?: return@withTimeout verificationFailed(plan)
                        val contact = localGateway.getContactByRawContact(raw.rawContactId)
                            ?: return@withTimeout verificationFailed(plan)
                        if (
                            !matches(requireNotNull(plan.write), observedRaw) ||
                            observedRaw.unknownRowsDigest != raw.unknownRowsDigest
                        ) {
                            return@withTimeout verificationFailed(plan)
                        }
                        mutationSucceeded(
                            plan,
                            buildJsonObject {
                                put("contact", contactDetails(taskId, contact))
                            },
                        )
                    }
                    ContactsToolAction.DELETE_CONTACT -> {
                        val raw = requireNotNull(plan.rawContact)
                        if (!localGateway.deleteContact(raw.rawContactId, raw.version)) {
                            return@withTimeout conflict(plan)
                        }
                        if (localGateway.getRawContact(raw.rawContactId) != null) {
                            return@withTimeout verificationFailed(plan)
                        }
                        handles.clearTask(taskId)
                        mutationSucceeded(
                            plan,
                            buildJsonObject { put("deleted", true) },
                        )
                    }
                    else -> error("Read action passed Contacts mutation execution boundary")
                }
            }
        } catch (cancelled: CancellationException) {
            if (providerCallIssued) outcomeUnknown(plan) else throw cancelled
        } catch (_: SecurityException) {
            handles.clearTask(taskId)
            if (providerCallIssued) outcomeUnknown(plan) else {
                capabilityNotReady(
                    plan.action.wireValue,
                    "Contacts write access is no longer available.",
                    "write",
                )
            }
        } catch (_: Exception) {
            if (providerCallIssued) outcomeUnknown(plan) else {
                failed(
                    plan.action.wireValue,
                    "PROVIDER_UNAVAILABLE",
                    "Android Contacts Provider is temporarily unavailable.",
                    true,
                )
            }
        }
    }

    override suspend fun execute(
        taskId: String,
        request: PiNativeToolRequest,
    ): PiNativeAndroidToolResult {
        if (taskId.isBlank() || !handles(request.toolName)) {
            return failed(null, "INVALID_ARGUMENTS", "Contacts arguments are invalid.", false)
        }
        val parsed = try {
            ContactsToolRequestParser.parse(request.arguments)
        } catch (_: ContactsToolArgumentsException) {
            val action = (request.arguments["action"] as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.contentOrNull
                ?.let(ContactsToolAction::fromWireValue)
                ?.wireValue
            return failed(action, "INVALID_ARGUMENTS", "Contacts arguments are invalid.", true)
        }
        val localGateway = gateway ?: return failed(
            parsed.action.wireValue,
            "PROVIDER_UNAVAILABLE",
            "Android Contacts Provider is not enabled in this build.",
            false,
        )
        if (parsed.action.isMutation) {
            return failed(
                parsed.action.wireValue,
                "INVALID_ARGUMENTS",
                "Contacts changes require an approved mutation plan.",
                false,
            )
        }
        val registry = capabilityRegistry ?: return failed(
            parsed.action.wireValue,
            "PROVIDER_UNAVAILABLE",
            "Android Contacts capability state is unavailable.",
            true,
        )
        val capability = registry.refreshNow().first { it.id == AndroidCapabilityId.CONTACTS }
        if (
            capability.availability != CapabilityAvailability.PARTIAL &&
            capability.availability != CapabilityAvailability.READY
        ) {
            handles.clearTask(taskId)
            return capabilityNotReady(parsed.action.wireValue, capability.safeMessage)
        }
        return try {
            withTimeout(timeoutMillis) {
                when (parsed) {
                    is ContactsToolRequest.Search -> search(taskId, parsed, localGateway)
                    is ContactsToolRequest.GetContact -> getContact(taskId, parsed, localGateway)
                    else -> error("Mutation request passed the Contacts read boundary")
                }
            }
        } catch (_: TimeoutCancellationException) {
            failed(
                parsed.action.wireValue,
                "DEVICE_TOOL_TIMEOUT",
                "Android Contacts lookup timed out.",
                true,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: StaleContactHandle) {
            failed(
                parsed.action.wireValue,
                "STALE_HANDLE",
                "The Contacts handle is no longer valid. Search live contacts again.",
                true,
            )
        } catch (_: ContactNotFound) {
            failed(
                parsed.action.wireValue,
                "NOT_FOUND",
                "The contact no longer exists.",
                false,
            )
        } catch (_: SecurityException) {
            handles.clearTask(taskId)
            capabilityNotReady(
                parsed.action.wireValue,
                "Contacts read access is no longer available.",
            )
        } catch (_: ContactsProviderUnavailable) {
            failed(
                parsed.action.wireValue,
                "PROVIDER_UNAVAILABLE",
                "Android Contacts Provider is temporarily unavailable.",
                true,
            )
        } catch (_: Exception) {
            failed(
                parsed.action.wireValue,
                "PROVIDER_UNAVAILABLE",
                "Android Contacts Provider is temporarily unavailable.",
                true,
            )
        }
    }

    override fun stopTask(taskId: String, reason: String) {
        if (reason == ANDROID_TOOL_TERMINAL_TURN_CLEANUP_REASON) return
        handles.clearTask(taskId)
    }

    private suspend fun search(
        taskId: String,
        request: ContactsToolRequest.Search,
        gateway: ContactsGateway,
    ): PiNativeAndroidToolResult {
        val fingerprint = handles.queryFingerprint(request.query)
        val offset = request.cursor?.let {
            handles.cursor(taskId, it, fingerprint) ?: throw StaleContactHandle()
        } ?: 0
        val page = gateway.search(
            query = request.query,
            offset = offset,
            limit = MAX_SEARCH_RESULTS,
        )
        val nextCursor = if (page.hasMore) {
            check(page.consumedCount > 0) { "Contacts provider did not advance the page" }
            handles.bindCursor(taskId, fingerprint, offset + page.consumedCount)
        } else {
            null
        }
        return succeeded(
            action = request.action.wireValue,
            data = buildJsonObject {
                put(
                    "items",
                    buildJsonArray {
                        page.items.take(MAX_SEARCH_RESULTS).forEach { record ->
                            add(contactSummary(taskId, record))
                        }
                    },
                )
                put("count", page.items.take(MAX_SEARCH_RESULTS).size)
            },
            page = buildJsonObject {
                put("truncated", page.hasMore)
                if (nextCursor == null) put("nextCursor", JsonNull)
                else put("nextCursor", nextCursor)
            },
        )
    }

    private suspend fun getContact(
        taskId: String,
        request: ContactsToolRequest.GetContact,
        gateway: ContactsGateway,
    ): PiNativeAndroidToolResult {
        val target = handles.contact(taskId, request.contactHandle)
            ?: throw StaleContactHandle()
        val contact = gateway.getContact(target) ?: throw ContactNotFound()
        return succeeded(
            action = request.action.wireValue,
            data = buildJsonObject {
                put("contact", contactDetails(taskId, contact))
            },
        )
    }

    private fun prepareCreate(
        taskId: String,
        request: PiNativeToolRequest,
        parsed: ContactsToolRequest.CreateContact,
    ): ContactsMutationPlan = mutationPlan(
        taskId = taskId,
        request = request,
        action = parsed.action,
        before = null,
        rawContact = null,
        write = parsed.write,
        fields = ContactMutationField.entries.toSet(),
        snapshotDigest = sha256("contacts-local-device-account-v1"),
        summary = "Create “${parsed.write.displayName.take(PREVIEW_NAME_CHARS)}”?",
        details = "Create one local-device contact and verify its supported fields.",
    )

    private suspend fun prepareUpdate(
        taskId: String,
        request: PiNativeToolRequest,
        parsed: ContactsToolRequest.UpdateContact,
        gateway: ContactsGateway,
    ): ContactsMutationPlan {
        val snapshot = snapshotForHandle(taskId, parsed.contactHandle, gateway)
        val raw = selectUpdateRawContact(snapshot)
        val write = raw.supported.apply(parsed.changes)
        val fields = parsed.changes.mutationFields()
        return mutationPlan(
            taskId = taskId,
            request = request,
            action = parsed.action,
            before = snapshot,
            rawContact = raw,
            write = write,
            fields = fields,
            snapshotDigest = snapshotDigest(snapshot),
            summary = "Update “${snapshot.aggregate.displayName.take(PREVIEW_NAME_CHARS)}”?",
            details = "Change ${fields.preview()} on one writable account record and preserve every omitted field.",
        )
    }

    private suspend fun prepareDelete(
        taskId: String,
        request: PiNativeToolRequest,
        parsed: ContactsToolRequest.DeleteContact,
        gateway: ContactsGateway,
    ): ContactsMutationPlan {
        val snapshot = snapshotForHandle(taskId, parsed.contactHandle, gateway)
        if (snapshot.rawContacts.isEmpty()) throw ContactReadOnlyTarget()
        if (snapshot.rawContacts.size != 1) throw ContactAmbiguousTarget()
        val raw = snapshot.rawContacts.single()
        return mutationPlan(
            taskId = taskId,
            request = request,
            action = parsed.action,
            before = snapshot,
            rawContact = raw,
            write = null,
            fields = emptySet(),
            snapshotDigest = snapshotDigest(snapshot),
            summary = "Delete “${snapshot.aggregate.displayName.take(PREVIEW_NAME_CHARS)}”?",
            details = "Permanently delete this single-account contact after a live conflict check.",
        )
    }

    private suspend fun snapshotForHandle(
        taskId: String,
        contactHandle: String,
        gateway: ContactsGateway,
    ): ContactMutationSnapshot {
        val target = handles.contact(taskId, contactHandle) ?: throw StaleContactHandle()
        return gateway.getMutationSnapshot(target) ?: throw ContactNotFound()
    }

    private fun selectUpdateRawContact(
        snapshot: ContactMutationSnapshot,
    ): ContactRawContactRecord {
        if (snapshot.rawContacts.isEmpty()) throw ContactReadOnlyTarget()
        if (snapshot.rawContacts.size == 1) return snapshot.rawContacts.single()
        val local = snapshot.rawContacts.filter(ContactRawContactRecord::localDeviceAccount)
        if (local.size == 1) return local.single()
        throw ContactAmbiguousTarget()
    }

    private fun ContactWrite.apply(changes: ContactChanges): ContactWrite = copy(
        displayName = changes.displayName ?: displayName,
        phones = changes.phones ?: phones,
        emails = changes.emails ?: emails,
        organization = when (val change = changes.organization) {
            ContactNullableChange.Unchanged -> organization
            ContactNullableChange.Clear -> null
            is ContactNullableChange.Set -> change.value
        },
    )

    private fun ContactChanges.mutationFields(): Set<ContactMutationField> = buildSet {
        if (displayName != null) add(ContactMutationField.DISPLAY_NAME)
        if (phones != null) add(ContactMutationField.PHONES)
        if (emails != null) add(ContactMutationField.EMAILS)
        if (organization !is ContactNullableChange.Unchanged) {
            add(ContactMutationField.ORGANIZATION)
        }
    }

    private fun Set<ContactMutationField>.preview(): String =
        sortedBy(ContactMutationField::ordinal).joinToString(", ") { field ->
            when (field) {
                ContactMutationField.DISPLAY_NAME -> "name"
                ContactMutationField.PHONES -> "phones"
                ContactMutationField.EMAILS -> "emails"
                ContactMutationField.ORGANIZATION -> "organization"
            }
        }

    private fun mutationPlan(
        taskId: String,
        request: PiNativeToolRequest,
        action: ContactsToolAction,
        before: ContactMutationSnapshot?,
        rawContact: ContactRawContactRecord?,
        write: ContactWrite?,
        fields: Set<ContactMutationField>,
        snapshotDigest: String,
        summary: String,
        details: String,
    ): ContactsMutationPlan {
        val requestDigest = requestDigest(request.arguments)
        val planDigest = sha256(
            listOf(
                taskId,
                request.toolCallId,
                action.wireValue,
                requestDigest,
                snapshotDigest,
                fields.sortedBy(ContactMutationField::ordinal)
                    .joinToString(",") { it.name },
            ).joinToString("\u001f"),
        )
        return ContactsMutationPlan(
            taskId = taskId,
            piToolCallId = request.toolCallId,
            action = action,
            requestDigest = requestDigest,
            snapshotDigest = snapshotDigest,
            planDigest = planDigest,
            summary = summary,
            details = details,
            before = before,
            rawContact = rawContact,
            write = write,
            fields = fields.toSet(),
        )
    }

    private suspend fun verifyPrecondition(
        plan: ContactsMutationPlan,
        gateway: ContactsGateway,
    ): PiNativeAndroidToolResult? {
        if (plan.before == null) return null
        val current = gateway.getMutationSnapshot(plan.before.aggregate.target)
            ?: return failed(
                plan.action.wireValue,
                "NOT_FOUND",
                "The contact no longer exists.",
                false,
            )
        return if (snapshotDigest(current) == plan.snapshotDigest) {
            null
        } else {
            conflict(plan)
        }
    }

    private suspend fun writeCapabilityReady(): Boolean {
        val registry = capabilityRegistry ?: return false
        return registry.refreshNow()
            .first { it.id == AndroidCapabilityId.CONTACTS }
            .availability == CapabilityAvailability.READY
    }

    private fun snapshotDigest(snapshot: ContactMutationSnapshot): String = sha256(
        buildString {
            append(contactWriteCanonical(snapshot.aggregate.asWrite()))
            snapshot.rawContacts.sortedBy(ContactRawContactRecord::rawContactId).forEach { raw ->
                append('\u001e')
                append(raw.rawContactId)
                append('\u001f')
                append(raw.version)
                append('\u001f')
                append(raw.accountKey)
                append('\u001f')
                append(contactWriteCanonical(raw.supported))
                append('\u001f')
                append(raw.unknownRowsDigest)
            }
        },
    )

    private fun ContactRecord.asWrite() = ContactWrite(
        displayName = displayName,
        phones = phones,
        emails = emails,
        organization = organization,
    )

    private fun matches(expected: ContactWrite, observed: ContactRawContactRecord): Boolean =
        contactWriteCanonical(expected) == contactWriteCanonical(observed.supported)

    private fun contactWriteCanonical(write: ContactWrite): String = listOf(
        write.displayName,
        write.phones.canonicalValues(),
        write.emails.canonicalValues(),
        write.organization?.company.orEmpty(),
        write.organization?.title.orEmpty(),
    ).joinToString("\u001f")

    private fun List<ContactValue>.canonicalValues(): String =
        sortedWith(
            compareByDescending<ContactValue>(ContactValue::primary)
                .thenBy { it.value.lowercase() }
                .thenBy { it.label.lowercase() },
        ).joinToString("\u001e") { value ->
            "${value.value}\u001f${value.label}\u001f${value.primary}"
        }

    private fun requestDigest(value: JsonObject): String = sha256(canonicalJson(value))

    private fun canonicalJson(value: kotlinx.serialization.json.JsonElement): String = when (value) {
        is JsonObject -> value.entries.sortedBy { it.key }.joinToString(
            prefix = "{",
            postfix = "}",
        ) { (key, item) -> "${JsonPrimitive(key)}:${canonicalJson(item)}" }
        is kotlinx.serialization.json.JsonArray -> value.joinToString(
            prefix = "[",
            postfix = "]",
        ) { canonicalJson(it) }
        else -> value.toString()
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun mutationSucceeded(
        plan: ContactsMutationPlan,
        data: JsonObject,
    ): PiNativeAndroidToolResult {
        val payload = buildJsonObject {
            put("ok", true)
            put("action", plan.action.wireValue)
            put("data", data)
            put(
                "verification",
                buildJsonObject {
                    put("status", "verified")
                    put("observedAt", observedAt())
                    put("planDigest", plan.planDigest)
                },
            )
        }
        check(payload.toString().encodeToByteArray().size <= MAX_RESULT_JSON_BYTES) {
            "Contacts mutation result exceeded its context budget"
        }
        return PiNativeAndroidToolResult(payload)
    }

    private fun conflict(plan: ContactsMutationPlan) = failed(
        plan.action.wireValue,
        "CONFLICT",
        "The contact changed after the mutation plan was prepared.",
        false,
    )

    private fun verificationFailed(plan: ContactsMutationPlan) = failed(
        plan.action.wireValue,
        "VERIFICATION_FAILED",
        "Android could not verify the requested Contacts change.",
        false,
    )

    private fun outcomeUnknown(plan: ContactsMutationPlan) = failed(
        plan.action.wireValue,
        "OUTCOME_UNKNOWN",
        "Android cannot prove whether the Contacts change completed. Inspect live data before retrying.",
        false,
    )

    private fun contactSummary(taskId: String, contact: ContactRecord) = buildJsonObject {
        val displayName = contact.displayName.boundedJsonText(MAX_DISPLAY_NAME_JSON_BYTES)
        put("contactHandle", handles.bindContact(taskId, contact.target))
        put("displayName", displayName.value)
        if (displayName.truncated) put("displayNameTruncated", true)
        put("phoneCount", contact.phones.size)
        put("emailCount", contact.emails.size)
    }

    private fun contactDetails(taskId: String, contact: ContactRecord): JsonObject {
        val displayName = contact.displayName.boundedJsonText(MAX_DISPLAY_NAME_JSON_BYTES)
        val phones = contact.phones.take(MAX_VALUES_PER_KIND).map {
            contactValue(it, MAX_PHONE_JSON_BYTES)
        }
        val emails = contact.emails.take(MAX_VALUES_PER_KIND).map {
            contactValue(it, MAX_EMAIL_JSON_BYTES)
        }
        val organization = contact.organization?.let(::organization)
        val truncated = displayName.truncated ||
            contact.phones.size > MAX_VALUES_PER_KIND ||
            contact.emails.size > MAX_VALUES_PER_KIND ||
            phones.any(BoundedJson::truncated) ||
            emails.any(BoundedJson::truncated) ||
            organization?.truncated == true
        return buildJsonObject {
            put("contactHandle", handles.bindContact(taskId, contact.target))
            put("displayName", displayName.value)
            put("phones", buildJsonArray { phones.forEach { add(it.value) } })
            put("emails", buildJsonArray { emails.forEach { add(it.value) } })
            if (organization == null) put("organization", JsonNull)
            else put("organization", organization.value)
            if (truncated) put("truncated", true)
        }
    }

    private fun contactValue(
        value: ContactValue,
        maximumValueJsonBytes: Int,
    ): BoundedJson {
        val boundedValue = value.value.boundedJsonText(maximumValueJsonBytes)
        val boundedLabel = value.label.boundedJsonText(MAX_LABEL_JSON_BYTES)
        return BoundedJson(
            value = buildJsonObject {
                put("value", boundedValue.value)
                put("label", boundedLabel.value)
                put("primary", value.primary)
            },
            truncated = boundedValue.truncated || boundedLabel.truncated,
        )
    }

    private fun organization(value: ContactOrganization): BoundedJson {
        val company = value.company?.boundedJsonText(MAX_ORGANIZATION_JSON_BYTES)
        val title = value.title?.boundedJsonText(MAX_TITLE_JSON_BYTES)
        return BoundedJson(
            value = buildJsonObject {
                company?.let { put("company", it.value) }
                title?.let { put("title", it.value) }
            },
            truncated = company?.truncated == true || title?.truncated == true,
        )
    }

    private fun succeeded(
        action: String,
        data: JsonObject,
        page: JsonObject? = null,
    ): PiNativeAndroidToolResult {
        val payload = buildJsonObject {
            put("ok", true)
            put("action", action)
            put("data", data)
            page?.let { put("page", it) }
            put(
                "verification",
                buildJsonObject {
                    put("status", "observed")
                    put(
                        "observedAt",
                        now().truncatedTo(ChronoUnit.MILLIS).toString(),
                    )
                },
            )
        }
        check(payload.toString().encodeToByteArray().size <= MAX_RESULT_JSON_BYTES) {
            "Contacts result exceeded its context budget"
        }
        return PiNativeAndroidToolResult(contentPayload = payload)
    }

    private fun observedAt(): String =
        now().truncatedTo(ChronoUnit.MILLIS).toString()

    private fun capabilityNotReady(
        action: String,
        message: String,
        requiredAccess: String = "read",
    ) = failed(
        action = action,
        code = "CAPABILITY_NOT_READY",
        message = message,
        retryable = true,
        resolution = buildJsonObject {
            put("kind", "request_capability")
            put("capability", "contacts")
            put("requiredAccess", requiredAccess)
        },
    )

    private fun failed(
        action: String?,
        code: String,
        message: String,
        retryable: Boolean,
        resolution: JsonObject? = null,
    ) = PiNativeAndroidToolResult(
        contentPayload = buildJsonObject {
            put("ok", false)
            if (action == null) put("action", JsonNull) else put("action", action)
            put(
                "error",
                buildJsonObject {
                    put("code", code)
                    put("message", message)
                    put("retryable", retryable)
                    resolution?.let { put("resolution", it) }
                },
            )
        },
        isError = true,
    )

    companion object {
        const val TOOL_NAME = "device_contacts"
        const val MAX_SEARCH_RESULTS = 10
        internal const val MAX_RESULT_JSON_BYTES = 12 * 1_024
        private const val MAX_VALUES_PER_KIND = 10
        private const val MAX_DISPLAY_NAME_JSON_BYTES = 384
        private const val MAX_PHONE_JSON_BYTES = 192
        private const val MAX_EMAIL_JSON_BYTES = 384
        private const val MAX_LABEL_JSON_BYTES = 64
        private const val MAX_ORGANIZATION_JSON_BYTES = 256
        private const val MAX_TITLE_JSON_BYTES = 192
        private const val PREVIEW_NAME_CHARS = 72
        private const val REQUEST_TIMEOUT_MILLIS = 5_000L

        fun create(
            context: Context,
            capabilityRegistry: AndroidCapabilityRegistry,
        ) = PhoneLocalContactsToolExecutor(
            gateway = AndroidContactsGateway.create(context),
            capabilityRegistry = capabilityRegistry,
        )
    }
}

private data class BoundedText(
    val value: String,
    val truncated: Boolean,
)

private data class BoundedJson(
    val value: JsonObject,
    val truncated: Boolean,
)

private fun String.boundedJsonText(maximumEncodedBytes: Int): BoundedText {
    require(maximumEncodedBytes >= 2)
    if (JsonPrimitive(this).toString().encodeToByteArray().size <= maximumEncodedBytes) {
        return BoundedText(this, false)
    }
    val bounded = StringBuilder()
    var offset = 0
    while (offset < length) {
        val previousLength = bounded.length
        val codePoint = codePointAt(offset)
        bounded.appendCodePoint(codePoint)
        if (
            JsonPrimitive(bounded.toString()).toString().encodeToByteArray().size >
            maximumEncodedBytes
        ) {
            bounded.setLength(previousLength)
            break
        }
        offset += Character.charCount(codePoint)
    }
    return BoundedText(bounded.toString(), true)
}

private class StaleContactHandle : IllegalArgumentException()
private class ContactNotFound : NoSuchElementException()
private class ContactReadOnlyTarget : IllegalStateException()
private class ContactAmbiguousTarget : IllegalStateException()
