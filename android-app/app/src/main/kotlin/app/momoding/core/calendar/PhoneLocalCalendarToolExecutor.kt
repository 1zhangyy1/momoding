package app.momoding.core.calendar

import android.content.Context
import app.momoding.core.capabilities.AndroidCapabilityId
import app.momoding.core.capabilities.AndroidCapabilityRegistry
import app.momoding.core.capabilities.CapabilityAvailability
import app.momoding.core.runtime.local.ANDROID_TOOL_TERMINAL_TURN_CLEANUP_REASON
import app.momoding.core.runtime.local.PiNativeAndroidToolResult
import app.momoding.core.runtime.local.PiNativeToolRequest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
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

interface PhoneLocalCalendarToolHandler {
    fun handles(toolName: String): Boolean
    fun isPersonalDataRead(request: PiNativeToolRequest): Boolean
    fun isMutation(request: PiNativeToolRequest): Boolean
    fun mutationRequestDigest(request: PiNativeToolRequest): String
    suspend fun isReadCapabilityReady(): Boolean
    suspend fun prepareMutation(
        taskId: String,
        request: PiNativeToolRequest,
    ): CalendarMutationPreparation
    suspend fun executeMutation(
        taskId: String,
        request: PiNativeToolRequest,
        plan: CalendarMutationPlan,
        onProviderDispatch: suspend () -> Unit,
    ): PiNativeAndroidToolResult
    suspend fun execute(taskId: String, request: PiNativeToolRequest): PiNativeAndroidToolResult
    fun stopTask(taskId: String, reason: String) = Unit
}

class PhoneLocalCalendarToolExecutor(
    private val gateway: CalendarGateway? = null,
    private val capabilityRegistry: AndroidCapabilityRegistry? = null,
    private val handles: CalendarHandleRegistry = CalendarHandleRegistry(),
    private val now: () -> Instant = Instant::now,
    private val timeoutMillis: Long = REQUEST_TIMEOUT_MILLIS,
) : PhoneLocalCalendarToolHandler {
    override fun handles(toolName: String): Boolean = toolName == TOOL_NAME

    override fun isPersonalDataRead(request: PiNativeToolRequest): Boolean =
        try {
            !CalendarToolRequestParser.parse(request.arguments).action.isMutation
        } catch (_: CalendarToolArgumentsException) {
            false
        }

    override fun isMutation(request: PiNativeToolRequest): Boolean =
        try {
            CalendarToolRequestParser.parse(request.arguments).action.isMutation
        } catch (_: CalendarToolArgumentsException) {
            false
        }

    override fun mutationRequestDigest(request: PiNativeToolRequest): String =
        requestDigest(request.arguments)

    override suspend fun isReadCapabilityReady(): Boolean {
        val registry = capabilityRegistry ?: return false
        return registry.refreshNow()
            .first { it.id == AndroidCapabilityId.CALENDAR }
            .availability in setOf(
                CapabilityAvailability.PARTIAL,
                CapabilityAvailability.READY,
            )
    }

    override suspend fun prepareMutation(
        taskId: String,
        request: PiNativeToolRequest,
    ): CalendarMutationPreparation {
        if (taskId.isBlank() || !handles(request.toolName)) {
            return CalendarMutationPreparation.Failed(
                failed(null, "INVALID_ARGUMENTS", "Calendar arguments are invalid", false),
            )
        }
        val parsed = try {
            CalendarToolRequestParser.parse(request.arguments)
        } catch (_: CalendarToolArgumentsException) {
            return CalendarMutationPreparation.Failed(
                failed(null, "INVALID_ARGUMENTS", "Calendar arguments are invalid", true),
            )
        }
        if (!parsed.action.isMutation) {
            return CalendarMutationPreparation.Failed(
                failed(
                    parsed.action.wireValue,
                    "INVALID_ARGUMENTS",
                    "Calendar mutation arguments are invalid.",
                    false,
                ),
            )
        }
        val localGateway = gateway ?: return CalendarMutationPreparation.Failed(
            failed(
                parsed.action.wireValue,
                "PROVIDER_UNAVAILABLE",
                "Android Calendar Provider is not enabled in this build stage.",
                false,
            ),
        )
        if (!writeCapabilityReady()) {
            return CalendarMutationPreparation.Failed(
                capabilityNotReady(
                    parsed.action.wireValue,
                    "Calendar write access is not enabled.",
                    "write",
                ),
            )
        }
        return try {
            CalendarMutationPreparation.Ready(
                when (parsed) {
                    is CalendarToolRequest.CreateEvent ->
                        prepareCreate(taskId, request, parsed, localGateway)
                    is CalendarToolRequest.UpdateEvent ->
                        prepareUpdate(taskId, request, parsed, localGateway)
                    is CalendarToolRequest.DeleteEvent ->
                        prepareDelete(taskId, request, parsed, localGateway)
                    else -> error("Read request passed the mutation boundary")
                },
            )
        } catch (_: StaleCalendarHandle) {
            CalendarMutationPreparation.Failed(
                failed(
                    parsed.action.wireValue,
                    "STALE_HANDLE",
                    "The Calendar handle is no longer valid. List live data again.",
                    true,
                ),
            )
        } catch (_: CalendarEventNotFound) {
            CalendarMutationPreparation.Failed(
                failed(
                    parsed.action.wireValue,
                    "NOT_FOUND",
                    "The Calendar event no longer exists.",
                    false,
                ),
            )
        } catch (_: CalendarReadOnlyTarget) {
            CalendarMutationPreparation.Failed(
                failed(
                    parsed.action.wireValue,
                    "READ_ONLY",
                    "The selected Calendar target is read-only.",
                    false,
                ),
            )
        } catch (_: CalendarAmbiguousTarget) {
            CalendarMutationPreparation.Failed(
                failed(
                    parsed.action.wireValue,
                    "AMBIGUOUS_TARGET",
                    "Choose one writable calendar before creating the event.",
                    true,
                    buildJsonObject {
                        put("kind", "call_tool")
                        put("tool", TOOL_NAME)
                        put("action", "list_calendars")
                    },
                ),
            )
        } catch (_: CalendarRecurringMutation) {
            CalendarMutationPreparation.Failed(
                failed(
                    parsed.action.wireValue,
                    "UNSUPPORTED_ACTION",
                    "Recurring Calendar events are not changed by this version.",
                    false,
                ),
            )
        } catch (_: SecurityException) {
            handles.clearTask(taskId)
            CalendarMutationPreparation.Failed(
                capabilityNotReady(
                    parsed.action.wireValue,
                    "Calendar write access is no longer available.",
                    "write",
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            CalendarMutationPreparation.Failed(
                failed(
                    parsed.action.wireValue,
                    "PROVIDER_UNAVAILABLE",
                    "Android Calendar Provider is temporarily unavailable.",
                    true,
                ),
            )
        }
    }

    override suspend fun executeMutation(
        taskId: String,
        request: PiNativeToolRequest,
        plan: CalendarMutationPlan,
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
                "The Calendar mutation no longer matches its approved plan.",
                false,
            )
        }
        val localGateway = gateway ?: return failed(
            plan.action.wireValue,
            "PROVIDER_UNAVAILABLE",
            "Android Calendar Provider is not enabled in this build stage.",
            false,
        )
        if (!writeCapabilityReady()) {
            return capabilityNotReady(
                plan.action.wireValue,
                "Calendar write access is not enabled.",
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
                    CalendarToolAction.CREATE_EVENT -> {
                        val eventId = localGateway.createEvent(requireNotNull(plan.write))
                        val observed = localGateway.getEventById(eventId)
                            ?: return@withTimeout verificationFailed(plan)
                        if (!matches(requireNotNull(plan.write), observed)) {
                            return@withTimeout verificationFailed(plan)
                        }
                        mutationSucceeded(
                            taskId,
                            plan,
                            buildJsonObject {
                                put("event", eventSummary(taskId, observed, includeDetails = true))
                            },
                        )
                    }
                    CalendarToolAction.UPDATE_EVENT -> {
                        val before = requireNotNull(plan.before)
                        if (!localGateway.updateEvent(
                                before.target.eventId,
                                requireNotNull(plan.write),
                            )
                        ) {
                            return@withTimeout verificationFailed(plan)
                        }
                        val observed = localGateway.getEventById(before.target.eventId)
                            ?: return@withTimeout verificationFailed(plan)
                        if (!matches(requireNotNull(plan.write), observed)) {
                            return@withTimeout verificationFailed(plan)
                        }
                        mutationSucceeded(
                            taskId,
                            plan,
                            buildJsonObject {
                                put("event", eventSummary(taskId, observed, includeDetails = true))
                            },
                        )
                    }
                    CalendarToolAction.DELETE_EVENT -> {
                        val before = requireNotNull(plan.before)
                        if (!localGateway.deleteEvent(before.target.eventId)) {
                            return@withTimeout verificationFailed(plan)
                        }
                        if (localGateway.getEventById(before.target.eventId) != null) {
                            return@withTimeout verificationFailed(plan)
                        }
                        handles.clearTask(taskId)
                        mutationSucceeded(
                            taskId,
                            plan,
                            buildJsonObject { put("deleted", true) },
                        )
                    }
                    else -> error("Read action passed the mutation execution boundary")
                }
            }
        } catch (cancelled: CancellationException) {
            if (providerCallIssued) {
                outcomeUnknown(plan)
            } else {
                throw cancelled
            }
        } catch (_: SecurityException) {
            handles.clearTask(taskId)
            if (providerCallIssued) outcomeUnknown(plan) else {
                capabilityNotReady(
                    plan.action.wireValue,
                    "Calendar write access is no longer available.",
                    "write",
                )
            }
        } catch (_: Exception) {
            if (providerCallIssued) outcomeUnknown(plan) else {
                failed(
                    plan.action.wireValue,
                    "PROVIDER_UNAVAILABLE",
                    "Android Calendar Provider is temporarily unavailable.",
                    true,
                )
            }
        }
    }

    override fun stopTask(taskId: String, reason: String) {
        if (reason == ANDROID_TOOL_TERMINAL_TURN_CLEANUP_REASON) return
        handles.clearTask(taskId)
    }

    override suspend fun execute(
        taskId: String,
        request: PiNativeToolRequest,
    ): PiNativeAndroidToolResult {
        if (taskId.isBlank() || !handles(request.toolName)) {
            return failed(null, "INVALID_ARGUMENTS", "Calendar arguments are invalid", false)
        }
        val parsed = try {
            CalendarToolRequestParser.parse(request.arguments)
        } catch (_: CalendarToolArgumentsException) {
            val action = (request.arguments["action"] as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.contentOrNull
                ?.let(CalendarToolAction::fromWireValue)
                ?.wireValue
            return failed(action, "INVALID_ARGUMENTS", "Calendar arguments are invalid", true)
        }
        if (parsed.action.isMutation) {
            return failed(
                parsed.action.wireValue,
                "UNSUPPORTED_ACTION",
                "Calendar changes are not enabled in this build stage.",
                false,
            )
        }
        val localGateway = gateway ?: return failed(
            parsed.action.wireValue,
            "PROVIDER_UNAVAILABLE",
            "Android Calendar Provider is not enabled in this build stage.",
            false,
        )
        val registry = capabilityRegistry ?: return failed(
            parsed.action.wireValue,
            "PROVIDER_UNAVAILABLE",
            "Android Calendar capability state is unavailable.",
            true,
        )
        val capability = registry.refreshNow().first {
            it.id == AndroidCapabilityId.CALENDAR
        }
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
                    is CalendarToolRequest.ListCalendars ->
                        listCalendars(taskId, parsed, localGateway)
                    is CalendarToolRequest.ListEvents ->
                        listEvents(taskId, parsed, localGateway)
                    is CalendarToolRequest.GetEvent ->
                        getEvent(taskId, parsed, localGateway)
                    else -> error("Mutation request passed the read-only Calendar boundary")
                }
            }
        } catch (_: TimeoutCancellationException) {
            failed(
                parsed.action.wireValue,
                "DEVICE_TOOL_TIMEOUT",
                "Android Calendar lookup timed out.",
                true,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: StaleCalendarHandle) {
            failed(
                parsed.action.wireValue,
                "STALE_HANDLE",
                "The Calendar handle is no longer valid. List live data again.",
                true,
            )
        } catch (_: CalendarEventNotFound) {
            failed(
                parsed.action.wireValue,
                "NOT_FOUND",
                "The Calendar event no longer exists.",
                false,
            )
        } catch (_: SecurityException) {
            handles.clearTask(taskId)
            capabilityNotReady(
                parsed.action.wireValue,
                "Calendar read access is no longer available.",
            )
        } catch (_: CalendarProviderUnavailable) {
            failed(
                parsed.action.wireValue,
                "PROVIDER_UNAVAILABLE",
                "Android Calendar Provider is temporarily unavailable.",
                true,
            )
        } catch (_: Exception) {
            failed(
                parsed.action.wireValue,
                "PROVIDER_UNAVAILABLE",
                "Android Calendar Provider is temporarily unavailable.",
                true,
            )
        }
    }

    private suspend fun listCalendars(
        taskId: String,
        request: CalendarToolRequest.ListCalendars,
        gateway: CalendarGateway,
    ): PiNativeAndroidToolResult {
        val records = gateway.listCalendars(MAX_CALENDARS + 1)
        val visible = records.take(MAX_CALENDARS)
        val accountLabels = linkedMapOf<String, String>()
        val items = buildJsonArray {
            visible.forEach { record ->
                val accountLabel = accountLabels.getOrPut(record.accountKey) {
                    "Account ${accountLabels.size + 1}"
                }
                add(
                    buildJsonObject {
                        put(
                            "calendarHandle",
                            handles.bindCalendar(taskId, record.id),
                        )
                        put("displayName", record.displayName)
                        put("accountLabel", accountLabel)
                        put("writable", record.writable)
                        record.primary?.let { put("primary", it) }
                        record.timeZone?.let { put("timeZone", it) }
                    },
                )
            }
        }
        return succeeded(
            request.action.wireValue,
            data = buildJsonObject {
                put("count", visible.size)
                put("items", items)
            },
            page = buildJsonObject {
                put("truncated", records.size > MAX_CALENDARS)
                put("nextCursor", JsonNull)
            },
        )
    }

    private suspend fun listEvents(
        taskId: String,
        request: CalendarToolRequest.ListEvents,
        gateway: CalendarGateway,
    ): PiNativeAndroidToolResult {
        val calendarId = request.calendarHandle?.let {
            handles.calendar(taskId, it) ?: throw StaleCalendarHandle()
        }
        val fingerprint = handles.queryFingerprint(
            request.start.toInstant().toEpochMilli(),
            request.end.toInstant().toEpochMilli(),
            calendarId,
            request.query,
        )
        val offset = request.cursor?.let {
            handles.cursor(taskId, it, fingerprint) ?: throw StaleCalendarHandle()
        } ?: 0
        val page = gateway.listEvents(
            startMillis = request.start.toInstant().toEpochMilli(),
            endMillis = request.end.toInstant().toEpochMilli(),
            calendarId = calendarId,
            query = request.query,
            offset = offset,
            limit = MAX_EVENTS,
        )
        val items = buildJsonArray {
            page.items.forEach { event ->
                add(eventSummary(taskId, event, includeDetails = false))
            }
        }
        val nextCursor = if (page.hasMore) {
            handles.bindCursor(taskId, fingerprint, offset + MAX_EVENTS)
        } else {
            null
        }
        return succeeded(
            request.action.wireValue,
            data = buildJsonObject {
                put("count", page.items.size)
                put("items", items)
            },
            page = buildJsonObject {
                put("truncated", page.hasMore)
                if (nextCursor == null) put("nextCursor", JsonNull)
                else put("nextCursor", nextCursor)
            },
        )
    }

    private suspend fun getEvent(
        taskId: String,
        request: CalendarToolRequest.GetEvent,
        gateway: CalendarGateway,
    ): PiNativeAndroidToolResult {
        val target = handles.event(taskId, request.eventHandle)
            ?: throw StaleCalendarHandle()
        val event = gateway.getEvent(target) ?: throw CalendarEventNotFound()
        return succeeded(
            request.action.wireValue,
            data = buildJsonObject {
                put("event", eventSummary(taskId, event, includeDetails = true))
            },
        )
    }

    private suspend fun prepareCreate(
        taskId: String,
        request: PiNativeToolRequest,
        parsed: CalendarToolRequest.CreateEvent,
        gateway: CalendarGateway,
    ): CalendarMutationPlan {
        val calendar = parsed.calendarHandle?.let { handle ->
            val id = handles.calendar(taskId, handle) ?: throw StaleCalendarHandle()
            gateway.getCalendar(id) ?: throw StaleCalendarHandle()
        } ?: selectWritableCalendar(gateway)
        if (!calendar.writable) throw CalendarReadOnlyTarget()
        val write = parsed.toWrite(calendar.id)
        return mutationPlan(
            taskId = taskId,
            request = request,
            action = parsed.action,
            calendar = calendar,
            before = null,
            write = write,
            snapshotDigest = calendarDigest(calendar),
            summary = "Create “${parsed.title.take(PREVIEW_TITLE_CHARS)}”?",
            details = "Create one ${write.scheduleLabel()} event in ${calendar.displayName}.",
        )
    }

    private suspend fun prepareUpdate(
        taskId: String,
        request: PiNativeToolRequest,
        parsed: CalendarToolRequest.UpdateEvent,
        gateway: CalendarGateway,
    ): CalendarMutationPlan {
        val target = handles.event(taskId, parsed.eventHandle) ?: throw StaleCalendarHandle()
        val before = gateway.getEventById(target.eventId) ?: throw CalendarEventNotFound()
        if (before.recurring) throw CalendarRecurringMutation()
        if (!before.calendarWritable) throw CalendarReadOnlyTarget()
        val calendar = gateway.getCalendar(before.calendarId) ?: throw CalendarEventNotFound()
        val write = before.apply(parsed.changes)
        return mutationPlan(
            taskId = taskId,
            request = request,
            action = parsed.action,
            calendar = calendar,
            before = before,
            write = write,
            snapshotDigest = eventDigest(before),
            summary = "Update “${before.displayTitle().take(PREVIEW_TITLE_CHARS)}”?",
            details = "Change ${parsed.changes.changedFields()} and verify the live event.",
        )
    }

    private suspend fun prepareDelete(
        taskId: String,
        request: PiNativeToolRequest,
        parsed: CalendarToolRequest.DeleteEvent,
        gateway: CalendarGateway,
    ): CalendarMutationPlan {
        val target = handles.event(taskId, parsed.eventHandle) ?: throw StaleCalendarHandle()
        val before = gateway.getEventById(target.eventId) ?: throw CalendarEventNotFound()
        if (before.recurring) throw CalendarRecurringMutation()
        if (!before.calendarWritable) throw CalendarReadOnlyTarget()
        val calendar = gateway.getCalendar(before.calendarId) ?: throw CalendarEventNotFound()
        return mutationPlan(
            taskId = taskId,
            request = request,
            action = parsed.action,
            calendar = calendar,
            before = before,
            write = null,
            snapshotDigest = eventDigest(before),
            summary = "Delete “${before.displayTitle().take(PREVIEW_TITLE_CHARS)}”?",
            details = "Permanently delete this one non-recurring event after a live conflict check.",
        )
    }

    private suspend fun selectWritableCalendar(gateway: CalendarGateway): CalendarRecord {
        val writable = gateway.listCalendars(MAX_CALENDARS + 1).filter { it.writable }
        if (writable.isEmpty()) throw CalendarReadOnlyTarget()
        if (writable.size == 1) return writable.single()
        val primary = writable.filter { it.primary == true }
        if (primary.size == 1) return primary.single()
        throw CalendarAmbiguousTarget()
    }

    private fun mutationPlan(
        taskId: String,
        request: PiNativeToolRequest,
        action: CalendarToolAction,
        calendar: CalendarRecord,
        before: CalendarEventRecord?,
        write: CalendarEventWrite?,
        snapshotDigest: String,
        summary: String,
        details: String,
    ): CalendarMutationPlan {
        val requestDigest = requestDigest(request.arguments)
        val planDigest = sha256(
            listOf(
                taskId,
                request.toolCallId,
                action.wireValue,
                requestDigest,
                snapshotDigest,
            ).joinToString("\u001f"),
        )
        return CalendarMutationPlan(
            taskId = taskId,
            piToolCallId = request.toolCallId,
            action = action,
            requestDigest = requestDigest,
            snapshotDigest = snapshotDigest,
            planDigest = planDigest,
            summary = summary,
            details = details,
            calendar = calendar,
            before = before,
            write = write,
        )
    }

    private suspend fun verifyPrecondition(
        plan: CalendarMutationPlan,
        gateway: CalendarGateway,
    ): PiNativeAndroidToolResult? {
        val currentDigest = if (plan.before == null) {
            val current = gateway.getCalendar(plan.calendar.id)
                ?: return failed(
                    plan.action.wireValue,
                    "NOT_FOUND",
                    "The selected calendar no longer exists.",
                    false,
                )
            if (!current.writable) {
                return failed(
                    plan.action.wireValue,
                    "READ_ONLY",
                    "The selected Calendar target is now read-only.",
                    false,
                )
            }
            calendarDigest(current)
        } else {
            val current = gateway.getEventById(plan.before.target.eventId)
                ?: return failed(
                    plan.action.wireValue,
                    "NOT_FOUND",
                    "The Calendar event no longer exists.",
                    false,
                )
            eventDigest(current)
        }
        return if (currentDigest == plan.snapshotDigest) {
            null
        } else {
            failed(
                plan.action.wireValue,
                "CONFLICT",
                "The Calendar target changed after the plan was prepared.",
                false,
            )
        }
    }

    private suspend fun writeCapabilityReady(): Boolean {
        val registry = capabilityRegistry ?: return false
        return registry.refreshNow()
            .first { it.id == AndroidCapabilityId.CALENDAR }
            .availability == CapabilityAvailability.READY
    }

    private fun CalendarToolRequest.CreateEvent.toWrite(calendarId: Long): CalendarEventWrite {
        val values = schedule.values()
        return CalendarEventWrite(
            calendarId = calendarId,
            title = title,
            startMillis = values.startMillis,
            endMillis = values.endMillis,
            allDay = values.allDay,
            timeZone = values.timeZone,
            location = location,
            description = description,
        )
    }

    private fun CalendarEventRecord.apply(changes: CalendarEventChanges): CalendarEventWrite {
        val schedule = changes.schedule?.values() ?: ScheduleValues(
            startMillis = startMillis,
            endMillis = endMillis,
            allDay = allDay,
            timeZone = if (allDay) "UTC" else timeZone ?: "UTC",
        )
        return CalendarEventWrite(
            calendarId = calendarId,
            title = changes.title ?: title,
            startMillis = schedule.startMillis,
            endMillis = schedule.endMillis,
            allDay = schedule.allDay,
            timeZone = schedule.timeZone,
            location = when (val change = changes.location) {
                CalendarNullableChange.Unchanged -> location
                CalendarNullableChange.Clear -> null
                is CalendarNullableChange.Set -> change.value
            },
            description = when (val change = changes.description) {
                CalendarNullableChange.Unchanged -> description
                CalendarNullableChange.Clear -> null
                is CalendarNullableChange.Set -> change.value
            },
        )
    }

    private fun CalendarSchedule.values(): ScheduleValues = when (this) {
        is CalendarSchedule.Timed -> ScheduleValues(
            startMillis = start.toInstant().toEpochMilli(),
            endMillis = end.toInstant().toEpochMilli(),
            allDay = false,
            timeZone = timeZone.id,
        )
        is CalendarSchedule.AllDay -> ScheduleValues(
            startMillis = startDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            endMillis = endDateExclusive.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            allDay = true,
            timeZone = "UTC",
        )
    }

    private fun CalendarEventChanges.changedFields(): String = buildList {
        if (title != null) add("title")
        if (schedule != null) add("schedule")
        if (location !is CalendarNullableChange.Unchanged) add("location")
        if (description !is CalendarNullableChange.Unchanged) add("description")
    }.joinToString(", ")

    private fun CalendarEventWrite.scheduleLabel(): String =
        if (allDay) "all-day" else "timed"

    private fun matches(expected: CalendarEventWrite, observed: CalendarEventRecord): Boolean =
        observed.calendarId == expected.calendarId &&
            observed.title == expected.title &&
            observed.startMillis == expected.startMillis &&
            observed.endMillis == expected.endMillis &&
            observed.allDay == expected.allDay &&
            (expected.allDay || observed.timeZone == expected.timeZone) &&
            observed.location == expected.location &&
            observed.description == expected.description &&
            !observed.recurring

    private fun calendarDigest(value: CalendarRecord): String = sha256(
        listOf(
            value.id,
            value.displayName,
            value.accountKey,
            value.writable,
            value.primary,
            value.timeZone,
        ).joinToString("\u001f"),
    )

    private fun eventDigest(value: CalendarEventRecord): String = sha256(
        listOf(
            value.target.eventId,
            value.calendarId,
            value.calendarDisplayName,
            value.calendarWritable,
            value.title,
            value.startMillis,
            value.endMillis,
            value.allDay,
            value.timeZone,
            value.location,
            value.description,
            value.recurring,
        ).joinToString("\u001f"),
    )

    private fun requestDigest(value: JsonObject): String = sha256(canonicalJson(value))

    private fun canonicalJson(value: kotlinx.serialization.json.JsonElement): String = when (value) {
        is JsonObject -> value.entries.sortedBy { it.key }.joinToString(
            prefix = "{",
            postfix = "}",
        ) { (key, item) ->
            "${JsonPrimitive(key)}:${canonicalJson(item)}"
        }
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
        taskId: String,
        plan: CalendarMutationPlan,
        data: JsonObject,
    ) = PiNativeAndroidToolResult(
        contentPayload = buildJsonObject {
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
        },
    )

    private fun verificationFailed(plan: CalendarMutationPlan) = failed(
        plan.action.wireValue,
        "VERIFICATION_FAILED",
        "Android could not verify the requested Calendar change.",
        false,
    )

    private fun outcomeUnknown(plan: CalendarMutationPlan) = failed(
        plan.action.wireValue,
        "OUTCOME_UNKNOWN",
        "Android cannot prove whether the Calendar change completed. Inspect live data before retrying.",
        false,
    )

    private fun eventSummary(
        taskId: String,
        event: CalendarEventRecord,
        includeDetails: Boolean,
    ): JsonObject = buildJsonObject {
        put("eventHandle", handles.bindEvent(taskId, event.target))
        put("title", event.displayTitle())
        put("schedule", event.scheduleJson())
        put(
            "calendar",
            buildJsonObject {
                put("calendarHandle", handles.bindCalendar(taskId, event.calendarId))
                put("displayName", event.calendarDisplayName)
            },
        )
        put("readOnly", !event.calendarWritable)
        put("recurring", event.recurring)
        if (includeDetails) {
            event.location?.takeIf(String::isNotBlank)?.let { put("location", it) }
            event.description?.takeIf(String::isNotBlank)?.let { put("description", it) }
        }
    }

    private fun CalendarEventRecord.displayTitle(): String =
        title.takeIf(String::isNotBlank) ?: "Untitled event"

    private fun CalendarEventRecord.scheduleJson(): JsonObject {
        val zone = timeZone
            ?.takeIf { it in ZoneId.getAvailableZoneIds() }
            ?.let(ZoneId::of)
            ?: ZoneOffset.UTC
        return if (allDay) {
            buildJsonObject {
                put("kind", "all_day")
                put(
                    "startDate",
                    Instant.ofEpochMilli(startMillis)
                        .atZone(ZoneOffset.UTC)
                        .toLocalDate()
                        .toString(),
                )
                put(
                    "endDateExclusive",
                    Instant.ofEpochMilli(endMillis)
                        .atZone(ZoneOffset.UTC)
                        .toLocalDate()
                        .toString(),
                )
                put("timeZone", zone.id)
            }
        } else {
            buildJsonObject {
                put("kind", "timed")
                put(
                    "start",
                    Instant.ofEpochMilli(startMillis).atZone(zone)
                        .toOffsetDateTime().format(RFC3339_MILLIS),
                )
                put(
                    "end",
                    Instant.ofEpochMilli(endMillis).atZone(zone)
                        .toOffsetDateTime().format(RFC3339_MILLIS),
                )
                put("timeZone", zone.id)
            }
        }
    }

    private fun succeeded(
        action: String,
        data: JsonObject,
        page: JsonObject? = null,
    ) = PiNativeAndroidToolResult(
        contentPayload = buildJsonObject {
            put("ok", true)
            put("action", action)
            put("data", data)
            page?.let { put("page", it) }
            put(
                "verification",
                buildJsonObject {
                    put("status", "observed")
                    put("observedAt", observedAt())
                },
            )
        },
    )

    private fun observedAt(): String = now()
        .truncatedTo(ChronoUnit.MILLIS)
        .toString()

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
            put("capability", "calendar")
            put("requiredAccess", requiredAccess)
        },
    )

    private fun failed(
        action: String?,
        code: String,
        message: String,
        retryable: Boolean,
        resolution: JsonObject? = null,
    ): PiNativeAndroidToolResult {
        val payload: JsonObject = buildJsonObject {
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
        }
        return PiNativeAndroidToolResult(contentPayload = payload, isError = true)
    }

    companion object {
        const val TOOL_NAME = "device_calendar"
        const val MAX_CALENDARS = 20
        const val MAX_EVENTS = 10
        private const val REQUEST_TIMEOUT_MILLIS = 5_000L
        private const val PREVIEW_TITLE_CHARS = 80
        private val RFC3339_MILLIS: DateTimeFormatter =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT)

        fun create(
            context: Context,
            capabilityRegistry: AndroidCapabilityRegistry,
        ) = PhoneLocalCalendarToolExecutor(
            gateway = AndroidCalendarGateway(context.applicationContext.contentResolver),
            capabilityRegistry = capabilityRegistry,
        )
    }
}

private data class ScheduleValues(
    val startMillis: Long,
    val endMillis: Long,
    val allDay: Boolean,
    val timeZone: String,
)

private class StaleCalendarHandle : IllegalArgumentException()
private class CalendarEventNotFound : NoSuchElementException()
private class CalendarReadOnlyTarget : IllegalStateException()
private class CalendarAmbiguousTarget : IllegalStateException()
private class CalendarRecurringMutation : IllegalStateException()
