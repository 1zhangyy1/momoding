package app.momoding.core.contacts

import app.momoding.core.capabilities.ContactsCapabilityAccess

enum class ContactsToolAction(
    val wireValue: String,
    val requiredAccess: ContactsCapabilityAccess,
    val isMutation: Boolean,
) {
    SEARCH("search", ContactsCapabilityAccess.READ, false),
    GET_CONTACT("get_contact", ContactsCapabilityAccess.READ, false),
    CREATE_CONTACT("create_contact", ContactsCapabilityAccess.WRITE, true),
    UPDATE_CONTACT("update_contact", ContactsCapabilityAccess.WRITE, true),
    DELETE_CONTACT("delete_contact", ContactsCapabilityAccess.WRITE, true),
    ;

    companion object {
        fun fromWireValue(value: String): ContactsToolAction? =
            entries.firstOrNull { it.wireValue == value }
    }
}

sealed interface ContactsToolRequest {
    val action: ContactsToolAction
    val purpose: String

    data class Search(
        override val purpose: String,
        val query: String,
        val cursor: String?,
    ) : ContactsToolRequest {
        override val action = ContactsToolAction.SEARCH
    }

    data class GetContact(
        override val purpose: String,
        val contactHandle: String,
    ) : ContactsToolRequest {
        override val action = ContactsToolAction.GET_CONTACT
    }

    data class CreateContact(
        override val purpose: String,
        val write: ContactWrite,
    ) : ContactsToolRequest {
        override val action = ContactsToolAction.CREATE_CONTACT
    }

    data class UpdateContact(
        override val purpose: String,
        val contactHandle: String,
        val changes: ContactChanges,
    ) : ContactsToolRequest {
        override val action = ContactsToolAction.UPDATE_CONTACT
    }

    data class DeleteContact(
        override val purpose: String,
        val contactHandle: String,
    ) : ContactsToolRequest {
        override val action = ContactsToolAction.DELETE_CONTACT
    }
}

data class ContactWrite(
    val displayName: String,
    val phones: List<ContactValue>,
    val emails: List<ContactValue>,
    val organization: ContactOrganization?,
)

enum class ContactMutationField {
    DISPLAY_NAME,
    PHONES,
    EMAILS,
    ORGANIZATION,
}

data class ContactChanges(
    val displayName: String? = null,
    val phones: List<ContactValue>? = null,
    val emails: List<ContactValue>? = null,
    val organization: ContactNullableChange<ContactOrganization> = ContactNullableChange.Unchanged,
) {
    init {
        require(
            displayName != null ||
                phones != null ||
                emails != null ||
                organization !is ContactNullableChange.Unchanged,
        ) { "Contacts update has no changes" }
    }
}

sealed interface ContactNullableChange<out T> {
    data object Unchanged : ContactNullableChange<Nothing>
    data object Clear : ContactNullableChange<Nothing>
    data class Set<T>(val value: T) : ContactNullableChange<T>
}

class ContactsToolArgumentsException(
    message: String = "Contacts arguments are invalid",
) : IllegalArgumentException(message)
