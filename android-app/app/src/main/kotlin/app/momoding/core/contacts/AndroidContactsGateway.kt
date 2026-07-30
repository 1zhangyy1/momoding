package app.momoding.core.contacts

import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.OperationApplicationException
import android.content.res.Resources
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.provider.ContactsContract
import java.security.MessageDigest
import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

class AndroidContactsGateway(
    private val resolver: ContentResolver,
    private val resources: Resources,
) : ContactsGateway {
    override suspend fun search(
        query: String,
        offset: Int,
        limit: Int,
    ): ContactPage = withContext(Dispatchers.IO) {
        require(query.isNotBlank() && query.length <= MAX_QUERY_CHARS)
        require(offset in 0..MAX_CURSOR_OFFSET)
        require(limit in 1..MAX_SEARCH_RESULTS)
        val uri = Uri.withAppendedPath(
            ContactsContract.Contacts.CONTENT_FILTER_URI,
            Uri.encode(query),
        )
        val scan = queryWithCancellation(
            uri = uri,
            projection = CONTACT_PROJECTION,
            queryArgs = Bundle().apply {
                putStringArray(
                    ContentResolver.QUERY_ARG_SORT_COLUMNS,
                    arrayOf(
                        ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                        ContactsContract.Contacts._ID,
                    ),
                )
                putInt(
                    ContentResolver.QUERY_ARG_SORT_DIRECTION,
                    ContentResolver.QUERY_SORT_DIRECTION_ASCENDING,
                )
                putInt(ContentResolver.QUERY_ARG_OFFSET, offset)
                putInt(ContentResolver.QUERY_ARG_LIMIT, limit + 1)
            },
        ) { cursor ->
            val id = cursor.column(ContactsContract.Contacts._ID)
            val lookup = cursor.column(ContactsContract.Contacts.LOOKUP_KEY)
            var physicalRows = 0
            val seeds = buildList {
                while (cursor.moveToNext() && physicalRows <= limit) {
                    physicalRows += 1
                    val lookupKey = cursor.nullableString(lookup)?.take(MAX_LOOKUP_KEY_CHARS)
                    if (physicalRows <= limit && !lookupKey.isNullOrBlank()) {
                        add(ContactTarget(cursor.getLong(id), lookupKey))
                    }
                }
            }
            ContactSearchScan(seeds = seeds, physicalRows = physicalRows)
        }
        val resolved = scan.seeds.mapNotNull { target -> resolveContact(target) }
        ContactPage(
            items = resolved,
            hasMore = scan.physicalRows > limit,
            consumedCount = minOf(limit, scan.physicalRows),
        )
    }

    override suspend fun getContact(target: ContactTarget): ContactRecord? =
        withContext(Dispatchers.IO) { resolveContact(target) }

    override suspend fun getMutationSnapshot(
        target: ContactTarget,
    ): ContactMutationSnapshot? = withContext(Dispatchers.IO) {
        val aggregate = resolveContact(target) ?: return@withContext null
        val seeds = queryRawContactSeeds(
            selection = "${ContactsContract.RawContacts.CONTACT_ID} = ? AND " +
                "${ContactsContract.RawContacts.DELETED} = 0",
            selectionArgs = arrayOf(aggregate.target.contactId.toString()),
            limit = MAX_RAW_CONTACTS + 1,
        )
        if (seeds.size > MAX_RAW_CONTACTS) throw ContactsProviderUnavailable()
        val rawContacts = seeds.map { seed -> loadRawContact(seed) }
        ContactMutationSnapshot(aggregate, rawContacts)
    }

    override suspend fun getRawContact(rawContactId: Long): ContactRawContactRecord? =
        withContext(Dispatchers.IO) {
            queryRawContactSeeds(
                selection = "${ContactsContract.RawContacts._ID} = ? AND " +
                    "${ContactsContract.RawContacts.DELETED} = 0",
                selectionArgs = arrayOf(rawContactId.toString()),
                limit = 1,
            ).singleOrNull()?.let { loadRawContact(it) }
        }

    override suspend fun getContactByRawContact(rawContactId: Long): ContactRecord? =
        withContext(Dispatchers.IO) {
            val raw = queryRawContactSeeds(
                selection = "${ContactsContract.RawContacts._ID} = ? AND " +
                    "${ContactsContract.RawContacts.DELETED} = 0",
                selectionArgs = arrayOf(rawContactId.toString()),
                limit = 1,
            ).singleOrNull() ?: return@withContext null
            val target = contactTarget(raw.contactId) ?: return@withContext null
            resolveContact(target)
        }

    override suspend fun createContact(write: ContactWrite): Long = blockingMutation {
        val operations = arrayListOf<ContentProviderOperation>()
        operations += ContentProviderOperation.newInsert(
            ContactsContract.RawContacts.CONTENT_URI,
        )
            .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
            .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
            .build()
        operations += write.dataInsertOperations(rawContactBackReference = 0)
        val results = resolver.applyBatch(ContactsContract.AUTHORITY, operations)
        results.firstOrNull()?.uri?.let(ContentUris::parseId)
            ?: throw ContactsProviderUnavailable()
    }

    override suspend fun updateContact(
        rawContactId: Long,
        expectedVersion: Long,
        write: ContactWrite,
        fields: Set<ContactMutationField>,
    ): Boolean = blockingMutation {
        require(fields.isNotEmpty())
        val operations = arrayListOf<ContentProviderOperation>()
        operations += ContentProviderOperation.newAssertQuery(
            ContactsContract.RawContacts.CONTENT_URI,
        )
            .withSelection(
                "${ContactsContract.RawContacts._ID} = ? AND " +
                    "${ContactsContract.RawContacts.VERSION} = ? AND " +
                    "${ContactsContract.RawContacts.DELETED} = 0",
                arrayOf(rawContactId.toString(), expectedVersion.toString()),
            )
            .withExpectedCount(1)
            .build()
        fields.map { it.mimeType() }.forEach { mimeType ->
            operations += ContentProviderOperation.newDelete(
                ContactsContract.Data.CONTENT_URI,
            )
                .withSelection(
                    "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND " +
                        "${ContactsContract.Data.MIMETYPE} = ?",
                    arrayOf(rawContactId.toString(), mimeType),
                )
                .build()
        }
        operations += write.dataInsertOperations(
            rawContactId = rawContactId,
            fields = fields,
        )
        try {
            resolver.applyBatch(ContactsContract.AUTHORITY, operations)
            true
        } catch (_: OperationApplicationException) {
            false
        }
    }

    override suspend fun deleteContact(
        rawContactId: Long,
        expectedVersion: Long,
    ): Boolean = blockingMutation {
        val operation = ContentProviderOperation.newDelete(
            ContactsContract.RawContacts.CONTENT_URI,
        )
            .withSelection(
                "${ContactsContract.RawContacts._ID} = ? AND " +
                    "${ContactsContract.RawContacts.VERSION} = ? AND " +
                    "${ContactsContract.RawContacts.DELETED} = 0",
                arrayOf(rawContactId.toString(), expectedVersion.toString()),
            )
            .withExpectedCount(1)
            .build()
        try {
            resolver.applyBatch(ContactsContract.AUTHORITY, arrayListOf(operation))
            true
        } catch (_: OperationApplicationException) {
            false
        }
    }

    private suspend fun queryRawContactSeeds(
        selection: String,
        selectionArgs: Array<String>,
        limit: Int,
    ): List<RawContactSeed> = queryWithCancellation(
        uri = ContactsContract.RawContacts.CONTENT_URI,
        projection = RAW_CONTACT_PROJECTION,
        queryArgs = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs)
            putStringArray(
                ContentResolver.QUERY_ARG_SORT_COLUMNS,
                arrayOf(ContactsContract.RawContacts._ID),
            )
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
        },
    ) { cursor ->
        val id = cursor.column(ContactsContract.RawContacts._ID)
        val contactId = cursor.column(ContactsContract.RawContacts.CONTACT_ID)
        val version = cursor.column(ContactsContract.RawContacts.VERSION)
        val accountName = cursor.column(ContactsContract.RawContacts.ACCOUNT_NAME)
        val accountType = cursor.column(ContactsContract.RawContacts.ACCOUNT_TYPE)
        buildList {
            while (cursor.moveToNext() && size < limit) {
                add(
                    RawContactSeed(
                        rawContactId = cursor.getLong(id),
                        contactId = cursor.getLong(contactId),
                        version = cursor.getLong(version),
                        accountName = cursor.nullableString(accountName),
                        accountType = cursor.nullableString(accountType),
                    ),
                )
            }
        }
    }

    private suspend fun loadRawContact(seed: RawContactSeed): ContactRawContactRecord {
        val rows = queryRawDataRows(seed.rawContactId)
        val ordered = rows.sortedWith(
            compareByDescending<RawContactDataRow>(RawContactDataRow::superPrimary)
                .thenByDescending(RawContactDataRow::primary)
                .thenBy(RawContactDataRow::dataId),
        )
        val displayName = ordered.firstOrNull {
            it.mimeType == ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE
        }?.data?.firstOrNull()?.bounded(MAX_DISPLAY_NAME)
            ?.takeIf(String::isNotBlank)
            ?: "Unnamed contact"
        return ContactRawContactRecord(
            rawContactId = seed.rawContactId,
            contactId = seed.contactId,
            version = seed.version,
            localDeviceAccount = seed.accountName.isNullOrBlank() &&
                seed.accountType.isNullOrBlank(),
            accountKey = sha256(
                "${seed.accountName.orEmpty()}\u001f${seed.accountType.orEmpty()}",
            ),
            supported = ContactWrite(
                displayName = displayName,
                phones = ordered
                    .filter {
                        it.mimeType ==
                            ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE
                    }
                    .mapNotNull(::rawPhoneValue)
                    .distinctBy { it.value.lowercase() }
                    .take(MAX_VALUES_PER_KIND),
                emails = ordered
                    .filter {
                        it.mimeType ==
                            ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE
                    }
                    .mapNotNull(::rawEmailValue)
                    .distinctBy { it.value.lowercase() }
                    .take(MAX_VALUES_PER_KIND),
                organization = ordered.firstNotNullOfOrNull(::rawOrganization),
            ),
            unknownRowsDigest = sha256(
                ordered.filter { it.mimeType !in SUPPORTED_MUTATION_MIME_TYPES }
                    .joinToString("\u001e") { row ->
                        listOf(row.dataId, row.mimeType, *row.data.toTypedArray())
                            .joinToString("\u001f")
                    },
            ),
        )
    }

    private suspend fun queryRawDataRows(rawContactId: Long): List<RawContactDataRow> {
        val rows = queryWithCancellation(
            uri = ContactsContract.Data.CONTENT_URI,
            projection = RAW_DATA_PROJECTION,
            queryArgs = Bundle().apply {
                putString(
                    ContentResolver.QUERY_ARG_SQL_SELECTION,
                    "${ContactsContract.Data.RAW_CONTACT_ID} = ?",
                )
                putStringArray(
                    ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                    arrayOf(rawContactId.toString()),
                )
                putStringArray(
                    ContentResolver.QUERY_ARG_SORT_COLUMNS,
                    arrayOf(ContactsContract.Data._ID),
                )
                putInt(ContentResolver.QUERY_ARG_LIMIT, MAX_RAW_DATA_ROWS + 1)
            },
        ) { cursor ->
            val id = cursor.column(ContactsContract.Data._ID)
            val mime = cursor.column(ContactsContract.Data.MIMETYPE)
            val primary = cursor.column(ContactsContract.Data.IS_PRIMARY)
            val superPrimary = cursor.column(ContactsContract.Data.IS_SUPER_PRIMARY)
            val dataColumns = RAW_DATA_COLUMNS.map { column -> cursor.column(column) }
            buildList {
                while (cursor.moveToNext() && size <= MAX_RAW_DATA_ROWS) {
                    add(
                        RawContactDataRow(
                            dataId = cursor.getLong(id),
                            mimeType = cursor.safeString(mime, MAX_MIME_CHARS),
                            data = dataColumns.map { column -> cursor.nullableString(column) },
                            primary = cursor.getInt(primary) != 0,
                            superPrimary = cursor.getInt(superPrimary) != 0,
                        ),
                    )
                }
            }
        }
        if (rows.size > MAX_RAW_DATA_ROWS) throw ContactsProviderUnavailable()
        return rows
    }

    private suspend fun contactTarget(contactId: Long): ContactTarget? =
        queryWithCancellation(
            uri = ContentUris.withAppendedId(
                ContactsContract.Contacts.CONTENT_URI,
                contactId,
            ),
            projection = CONTACT_PROJECTION,
            queryArgs = Bundle().apply {
                putInt(ContentResolver.QUERY_ARG_LIMIT, 1)
            },
        ) { cursor ->
            if (!cursor.moveToFirst()) {
                null
            } else {
                val id = cursor.column(ContactsContract.Contacts._ID)
                val lookup = cursor.column(ContactsContract.Contacts.LOOKUP_KEY)
                cursor.nullableString(lookup)
                    ?.take(MAX_LOOKUP_KEY_CHARS)
                    ?.takeIf(String::isNotBlank)
                    ?.let { ContactTarget(cursor.getLong(id), it) }
            }
        }

    private suspend fun resolveContact(target: ContactTarget): ContactRecord? {
        val lookupUri = ContactsContract.Contacts.getLookupUri(
            target.contactId,
            target.lookupKey,
        )
        val seed = queryWithCancellation(
            uri = lookupUri,
            projection = CONTACT_PROJECTION,
            queryArgs = Bundle().apply {
                putInt(ContentResolver.QUERY_ARG_LIMIT, 1)
            },
        ) { cursor ->
            if (!cursor.moveToFirst()) {
                null
            } else {
                val id = cursor.column(ContactsContract.Contacts._ID)
                val lookup = cursor.column(ContactsContract.Contacts.LOOKUP_KEY)
                val displayName =
                    cursor.column(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
                val lookupKey = cursor.nullableString(lookup)?.take(MAX_LOOKUP_KEY_CHARS)
                if (lookupKey.isNullOrBlank()) {
                    null
                } else {
                    ContactSeed(
                        target = ContactTarget(cursor.getLong(id), lookupKey),
                        displayName = cursor.safeString(displayName, MAX_DISPLAY_NAME)
                            .ifBlank { "Unnamed contact" },
                    )
                }
            }
        } ?: return null
        return loadContactData(seed)
    }

    private suspend fun loadContactData(seed: ContactSeed): ContactRecord {
        val rows = queryWithCancellation(
            uri = ContactsContract.Data.CONTENT_URI,
            projection = DATA_PROJECTION,
            queryArgs = Bundle().apply {
                putString(
                    ContentResolver.QUERY_ARG_SQL_SELECTION,
                    "${ContactsContract.Data.CONTACT_ID} = ? AND " +
                        "${ContactsContract.Data.MIMETYPE} IN (?,?,?)",
                )
                putStringArray(
                    ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                    arrayOf(
                        seed.target.contactId.toString(),
                        ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
                        ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
                        ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE,
                    ),
                )
                putStringArray(
                    ContentResolver.QUERY_ARG_SORT_COLUMNS,
                    arrayOf(ContactsContract.Data._ID),
                )
                putInt(
                    ContentResolver.QUERY_ARG_SORT_DIRECTION,
                    ContentResolver.QUERY_SORT_DIRECTION_ASCENDING,
                )
                putInt(ContentResolver.QUERY_ARG_LIMIT, MAX_DATA_ROWS)
            },
        ) { cursor -> cursor.contactDataRows() }
        val ordered = rows.sortedWith(
            compareByDescending<ContactDataRow>(ContactDataRow::superPrimary)
                .thenByDescending(ContactDataRow::primary)
                .thenBy(ContactDataRow::dataId),
        )
        return ContactRecord(
            target = seed.target,
            displayName = seed.displayName,
            phones = ordered
                .filter { it.mimeType == ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE }
                .mapNotNull(::phoneValue)
                .distinctBy { it.value.lowercase() }
                .take(MAX_VALUES_PER_KIND),
            emails = ordered
                .filter { it.mimeType == ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE }
                .mapNotNull(::emailValue)
                .distinctBy { it.value.lowercase() }
                .take(MAX_VALUES_PER_KIND),
            organization = ordered
                .asSequence()
                .filter {
                    it.mimeType == ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE
                }
                .mapNotNull(::organization)
                .firstOrNull(),
        )
    }

    private fun phoneValue(row: ContactDataRow): ContactValue? {
        val number = row.data1?.bounded(MAX_PHONE_CHARS)?.takeIf(String::isNotBlank) ?: return null
        return ContactValue(
            value = number,
            label = ContactsContract.CommonDataKinds.Phone.getTypeLabel(
                resources,
                row.data2?.toIntOrNull()
                    ?: ContactsContract.CommonDataKinds.Phone.TYPE_OTHER,
                row.data3,
            ).toString().bounded(MAX_LABEL_CHARS),
            primary = row.primary || row.superPrimary,
        )
    }

    private fun emailValue(row: ContactDataRow): ContactValue? {
        val address = row.data1?.bounded(MAX_EMAIL_CHARS)?.takeIf(String::isNotBlank) ?: return null
        return ContactValue(
            value = address,
            label = ContactsContract.CommonDataKinds.Email.getTypeLabel(
                resources,
                row.data2?.toIntOrNull()
                    ?: ContactsContract.CommonDataKinds.Email.TYPE_OTHER,
                row.data3,
            ).toString().bounded(MAX_LABEL_CHARS),
            primary = row.primary || row.superPrimary,
        )
    }

    private fun organization(row: ContactDataRow): ContactOrganization? {
        val company = row.data1?.bounded(MAX_ORGANIZATION_CHARS)?.takeIf(String::isNotBlank)
        val title = row.data4?.bounded(MAX_TITLE_CHARS)?.takeIf(String::isNotBlank)
        return if (company == null && title == null) null else {
            ContactOrganization(company = company, title = title)
        }
    }

    private fun rawPhoneValue(row: RawContactDataRow): ContactValue? {
        val number = row.data.getOrNull(0)
            ?.bounded(MAX_PHONE_CHARS)
            ?.takeIf(String::isNotBlank)
            ?: return null
        return ContactValue(
            value = number,
            label = ContactsContract.CommonDataKinds.Phone.getTypeLabel(
                resources,
                row.data.getOrNull(1)?.toIntOrNull()
                    ?: ContactsContract.CommonDataKinds.Phone.TYPE_OTHER,
                row.data.getOrNull(2),
            ).toString().bounded(MAX_LABEL_CHARS),
            primary = row.primary || row.superPrimary,
        )
    }

    private fun rawEmailValue(row: RawContactDataRow): ContactValue? {
        val address = row.data.getOrNull(0)
            ?.bounded(MAX_EMAIL_CHARS)
            ?.takeIf(String::isNotBlank)
            ?: return null
        return ContactValue(
            value = address,
            label = ContactsContract.CommonDataKinds.Email.getTypeLabel(
                resources,
                row.data.getOrNull(1)?.toIntOrNull()
                    ?: ContactsContract.CommonDataKinds.Email.TYPE_OTHER,
                row.data.getOrNull(2),
            ).toString().bounded(MAX_LABEL_CHARS),
            primary = row.primary || row.superPrimary,
        )
    }

    private fun rawOrganization(row: RawContactDataRow): ContactOrganization? {
        if (
            row.mimeType !=
            ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE
        ) {
            return null
        }
        val company = row.data.getOrNull(0)
            ?.bounded(MAX_ORGANIZATION_CHARS)
            ?.takeIf(String::isNotBlank)
        val title = row.data.getOrNull(3)
            ?.bounded(MAX_TITLE_CHARS)
            ?.takeIf(String::isNotBlank)
        return if (company == null && title == null) null else {
            ContactOrganization(company, title)
        }
    }

    private fun ContactWrite.dataInsertOperations(
        rawContactId: Long? = null,
        rawContactBackReference: Int? = null,
        fields: Set<ContactMutationField> = ContactMutationField.entries.toSet(),
    ): List<ContentProviderOperation> {
        require((rawContactId == null) != (rawContactBackReference == null))
        require(fields.isNotEmpty())
        fun ContentProviderOperation.Builder.bindRawContact(): ContentProviderOperation.Builder =
            if (rawContactId != null) {
                withValue(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
            } else {
                withValueBackReference(
                    ContactsContract.Data.RAW_CONTACT_ID,
                    requireNotNull(rawContactBackReference),
                )
            }
        return buildList {
            if (ContactMutationField.DISPLAY_NAME in fields) {
                add(
                    ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .bindRawContact()
                        .withValue(
                            ContactsContract.Data.MIMETYPE,
                            ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
                        )
                        .withValue(
                            ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME,
                            displayName,
                        )
                        .build(),
                )
            }
            if (ContactMutationField.PHONES in fields) {
                phones.forEach { phone ->
                    add(
                        ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                            .bindRawContact()
                            .withValue(
                                ContactsContract.Data.MIMETYPE,
                                ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
                            )
                            .withValue(
                                ContactsContract.CommonDataKinds.Phone.NUMBER,
                                phone.value,
                            )
                            .withValue(
                                ContactsContract.CommonDataKinds.Phone.TYPE,
                                ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM,
                            )
                            .withValue(
                                ContactsContract.CommonDataKinds.Phone.LABEL,
                                phone.label,
                            )
                            .withValue(
                                ContactsContract.Data.IS_PRIMARY,
                                if (phone.primary) 1 else 0,
                            )
                            .withValue(
                                ContactsContract.Data.IS_SUPER_PRIMARY,
                                if (phone.primary) 1 else 0,
                            )
                            .build(),
                    )
                }
            }
            if (ContactMutationField.EMAILS in fields) {
                emails.forEach { email ->
                    add(
                        ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                            .bindRawContact()
                            .withValue(
                                ContactsContract.Data.MIMETYPE,
                                ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
                            )
                            .withValue(
                                ContactsContract.CommonDataKinds.Email.ADDRESS,
                                email.value,
                            )
                            .withValue(
                                ContactsContract.CommonDataKinds.Email.TYPE,
                                ContactsContract.CommonDataKinds.Email.TYPE_CUSTOM,
                            )
                            .withValue(
                                ContactsContract.CommonDataKinds.Email.LABEL,
                                email.label,
                            )
                            .withValue(
                                ContactsContract.Data.IS_PRIMARY,
                                if (email.primary) 1 else 0,
                            )
                            .withValue(
                                ContactsContract.Data.IS_SUPER_PRIMARY,
                                if (email.primary) 1 else 0,
                            )
                            .build(),
                    )
                }
            }
            if (ContactMutationField.ORGANIZATION in fields) {
                organization?.let { organization ->
                    add(
                        ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                            .bindRawContact()
                            .withValue(
                                ContactsContract.Data.MIMETYPE,
                                ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE,
                            )
                            .withValue(
                                ContactsContract.CommonDataKinds.Organization.COMPANY,
                                organization.company,
                            )
                            .withValue(
                                ContactsContract.CommonDataKinds.Organization.TITLE,
                                organization.title,
                            )
                            .build(),
                    )
                }
            }
        }
    }

    private fun ContactMutationField.mimeType(): String = when (this) {
        ContactMutationField.DISPLAY_NAME ->
            ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE
        ContactMutationField.PHONES ->
            ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE
        ContactMutationField.EMAILS ->
            ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE
        ContactMutationField.ORGANIZATION ->
            ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE
    }

    /**
     * Contacts mutations use applyBatch without CancellationSignal. The durable dispatch fence in
     * the caller makes cancellation after dispatch outcome-unknown and prevents automatic replay.
     */
    private suspend fun <T> blockingMutation(block: () -> T): T =
        suspendCancellableCoroutine { continuation ->
            val task = FutureTask {
                try {
                    val result = block()
                    if (continuation.isActive) {
                        continuation.resumeWith(Result.success(result))
                    }
                } catch (error: Throwable) {
                    if (continuation.isActive) {
                        continuation.resumeWith(Result.failure(error))
                    }
                }
            }
            try {
                MUTATION_EXECUTOR.execute(task)
            } catch (error: RejectedExecutionException) {
                continuation.resumeWith(Result.failure(error))
                return@suspendCancellableCoroutine
            }
            continuation.invokeOnCancellation {
                task.cancel(true)
                MUTATION_EXECUTOR.remove(task)
            }
        }

    private suspend fun <T> queryWithCancellation(
        uri: Uri,
        projection: Array<String>,
        queryArgs: Bundle,
        transform: (android.database.Cursor) -> T,
    ): T {
        val cancellation = CancellationSignal()
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { cancellation.cancel() }
            try {
                val cursor = resolver.query(uri, projection, queryArgs, cancellation)
                    ?: throw ContactsProviderUnavailable()
                val result = cursor.use(transform)
                continuation.resumeWith(Result.success(result))
            } catch (error: Throwable) {
                if (continuation.isActive) {
                    continuation.resumeWith(Result.failure(error))
                }
            }
        }
    }

    private fun android.database.Cursor.contactDataRows(): List<ContactDataRow> {
        val id = column(ContactsContract.Data._ID)
        val mime = column(ContactsContract.Data.MIMETYPE)
        val data1 = column(ContactsContract.Data.DATA1)
        val data2 = column(ContactsContract.Data.DATA2)
        val data3 = column(ContactsContract.Data.DATA3)
        val data4 = column(ContactsContract.Data.DATA4)
        val primary = column(ContactsContract.Data.IS_PRIMARY)
        val superPrimary = column(ContactsContract.Data.IS_SUPER_PRIMARY)
        return buildList {
            while (moveToNext() && size < MAX_DATA_ROWS) {
                add(
                    ContactDataRow(
                        dataId = getLong(id),
                        mimeType = safeString(mime, MAX_MIME_CHARS),
                        data1 = nullableString(data1),
                        data2 = nullableString(data2),
                        data3 = nullableString(data3),
                        data4 = nullableString(data4),
                        primary = getInt(primary) != 0,
                        superPrimary = getInt(superPrimary) != 0,
                    ),
                )
            }
        }
    }

    private fun android.database.Cursor.column(name: String): Int = getColumnIndexOrThrow(name)

    private fun android.database.Cursor.safeString(index: Int, maximum: Int): String =
        nullableString(index)?.bounded(maximum).orEmpty()

    private fun android.database.Cursor.nullableString(index: Int): String? =
        if (isNull(index)) null else getString(index)

    private fun String.bounded(maximum: Int): String =
        trim().replace(CONTROL_CHARACTERS, " ").take(maximum)

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    companion object {
        fun create(context: Context) = AndroidContactsGateway(
            resolver = context.applicationContext.contentResolver,
            resources = context.applicationContext.resources,
        )

        private const val MAX_QUERY_CHARS = 120
        private const val MAX_CURSOR_OFFSET = 10_000
        private const val MAX_SEARCH_RESULTS = 20
        private const val MAX_DATA_ROWS = 128
        private const val MAX_RAW_CONTACTS = 32
        private const val MAX_RAW_DATA_ROWS = 256
        private const val MAX_VALUES_PER_KIND = 10
        private const val MAX_LOOKUP_KEY_CHARS = 512
        private const val MAX_DISPLAY_NAME = 200
        private const val MAX_PHONE_CHARS = 128
        private const val MAX_EMAIL_CHARS = 320
        private const val MAX_LABEL_CHARS = 64
        private const val MAX_ORGANIZATION_CHARS = 256
        private const val MAX_TITLE_CHARS = 160
        private const val MAX_MIME_CHARS = 160
        private val CONTROL_CHARACTERS = Regex("[\\p{Cc}\\p{Cf}]+")
        private val CONTACT_PROJECTION = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.LOOKUP_KEY,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
        )
        private val DATA_PROJECTION = arrayOf(
            ContactsContract.Data._ID,
            ContactsContract.Data.MIMETYPE,
            ContactsContract.Data.DATA1,
            ContactsContract.Data.DATA2,
            ContactsContract.Data.DATA3,
            ContactsContract.Data.DATA4,
            ContactsContract.Data.IS_PRIMARY,
            ContactsContract.Data.IS_SUPER_PRIMARY,
        )
        private val RAW_CONTACT_PROJECTION = arrayOf(
            ContactsContract.RawContacts._ID,
            ContactsContract.RawContacts.CONTACT_ID,
            ContactsContract.RawContacts.VERSION,
            ContactsContract.RawContacts.ACCOUNT_NAME,
            ContactsContract.RawContacts.ACCOUNT_TYPE,
        )
        private val RAW_DATA_COLUMNS = listOf(
            ContactsContract.Data.DATA1,
            ContactsContract.Data.DATA2,
            ContactsContract.Data.DATA3,
            ContactsContract.Data.DATA4,
            ContactsContract.Data.DATA5,
            ContactsContract.Data.DATA6,
            ContactsContract.Data.DATA7,
            ContactsContract.Data.DATA8,
            ContactsContract.Data.DATA9,
            ContactsContract.Data.DATA10,
            ContactsContract.Data.DATA11,
            ContactsContract.Data.DATA12,
            ContactsContract.Data.DATA13,
            ContactsContract.Data.DATA14,
            ContactsContract.Data.DATA15,
        )
        private val RAW_DATA_PROJECTION = arrayOf(
            ContactsContract.Data._ID,
            ContactsContract.Data.MIMETYPE,
            ContactsContract.Data.IS_PRIMARY,
            ContactsContract.Data.IS_SUPER_PRIMARY,
            *RAW_DATA_COLUMNS.toTypedArray(),
        )
        private val SUPPORTED_MUTATION_MIME_TYPES = setOf(
            ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE,
        )
        private val MUTATION_EXECUTOR = ThreadPoolExecutor(
            1,
            2,
            30L,
            TimeUnit.SECONDS,
            LinkedBlockingQueue(16),
        ).apply {
            allowCoreThreadTimeOut(true)
        }
    }
}

private data class ContactSeed(
    val target: ContactTarget,
    val displayName: String,
)

private data class ContactSearchScan(
    val seeds: List<ContactTarget>,
    val physicalRows: Int,
)

private data class RawContactSeed(
    val rawContactId: Long,
    val contactId: Long,
    val version: Long,
    val accountName: String?,
    val accountType: String?,
)

private data class RawContactDataRow(
    val dataId: Long,
    val mimeType: String,
    val data: List<String?>,
    val primary: Boolean,
    val superPrimary: Boolean,
)

private data class ContactDataRow(
    val dataId: Long,
    val mimeType: String,
    val data1: String?,
    val data2: String?,
    val data3: String?,
    val data4: String?,
    val primary: Boolean,
    val superPrimary: Boolean,
)
