package app.momoding.core.contacts

interface ContactsGateway {
    suspend fun search(
        query: String,
        offset: Int,
        limit: Int,
    ): ContactPage

    suspend fun getContact(target: ContactTarget): ContactRecord?

    suspend fun getMutationSnapshot(target: ContactTarget): ContactMutationSnapshot? = null

    suspend fun getRawContact(rawContactId: Long): ContactRawContactRecord? = null

    suspend fun getContactByRawContact(rawContactId: Long): ContactRecord? = null

    suspend fun createContact(write: ContactWrite): Long =
        throw ContactsProviderUnavailable()

    suspend fun updateContact(
        rawContactId: Long,
        expectedVersion: Long,
        write: ContactWrite,
        fields: Set<ContactMutationField>,
    ): Boolean = throw ContactsProviderUnavailable()

    suspend fun deleteContact(
        rawContactId: Long,
        expectedVersion: Long,
    ): Boolean = throw ContactsProviderUnavailable()
}

data class ContactTarget(
    val contactId: Long,
    val lookupKey: String,
)

data class ContactPage(
    val items: List<ContactRecord>,
    val hasMore: Boolean,
    /** Number of provider rows consumed, including rows that disappeared during hydration. */
    val consumedCount: Int,
)

data class ContactRecord(
    val target: ContactTarget,
    val displayName: String,
    val phones: List<ContactValue>,
    val emails: List<ContactValue>,
    val organization: ContactOrganization?,
)

data class ContactValue(
    val value: String,
    val label: String,
    val primary: Boolean,
)

data class ContactOrganization(
    val company: String?,
    val title: String?,
)

data class ContactMutationSnapshot(
    val aggregate: ContactRecord,
    val rawContacts: List<ContactRawContactRecord>,
)

data class ContactRawContactRecord(
    val rawContactId: Long,
    val contactId: Long,
    val version: Long,
    val localDeviceAccount: Boolean,
    val accountKey: String,
    val supported: ContactWrite,
    val unknownRowsDigest: String,
)

class ContactsProviderUnavailable : IllegalStateException()
