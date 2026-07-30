package app.momoding.core.contacts

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

object ContactsToolRequestParser {
    private const val MAX_PURPOSE_CHARS = 160
    private const val MAX_QUERY_CHARS = 120
    private const val MAX_DISPLAY_NAME_CHARS = 200
    private const val MAX_PHONE_CHARS = 128
    private const val MAX_EMAIL_CHARS = 320
    private const val MAX_LABEL_CHARS = 64
    private const val MAX_ORGANIZATION_CHARS = 256
    private const val MAX_TITLE_CHARS = 160
    private const val MAX_VALUES_PER_KIND = 10
    private val CONTACT_HANDLE = Regex("^contact-[0-9a-f]{24}$")
    private val PAGE_CURSOR = Regex("^contacts-page-[A-Za-z0-9_-]+$")

    fun parse(arguments: JsonObject): ContactsToolRequest {
        val action = ContactsToolAction.fromWireValue(
            arguments.requiredString("action", 32),
        ) ?: invalid()
        val purpose = arguments.requiredString("purpose", MAX_PURPOSE_CHARS)
        return when (action) {
            ContactsToolAction.SEARCH -> {
                arguments.requireExactKeys("action", "purpose", "query", "cursor")
                ContactsToolRequest.Search(
                    purpose = purpose,
                    query = arguments.requiredString("query", MAX_QUERY_CHARS),
                    cursor = arguments.nullableString("cursor", 160)?.also {
                        if (!PAGE_CURSOR.matches(it)) invalid()
                    },
                )
            }
            ContactsToolAction.GET_CONTACT -> {
                arguments.requireExactKeys("action", "purpose", "contactHandle")
                ContactsToolRequest.GetContact(
                    purpose = purpose,
                    contactHandle = arguments.requiredString("contactHandle", 160).also {
                        if (!CONTACT_HANDLE.matches(it)) invalid()
                    },
                )
            }
            ContactsToolAction.CREATE_CONTACT -> {
                arguments.requireExactKeys(
                    "action",
                    "purpose",
                    "displayName",
                    "phones",
                    "emails",
                    "organization",
                )
                ContactsToolRequest.CreateContact(
                    purpose = purpose,
                    write = ContactWrite(
                        displayName = arguments.requiredString(
                            "displayName",
                            MAX_DISPLAY_NAME_CHARS,
                        ),
                        phones = arguments.requiredValues(
                            "phones",
                            MAX_PHONE_CHARS,
                        ),
                        emails = arguments.requiredValues(
                            "emails",
                            MAX_EMAIL_CHARS,
                        ),
                        organization = arguments.nullableOrganization("organization"),
                    ),
                )
            }
            ContactsToolAction.UPDATE_CONTACT -> {
                arguments.requireExactKeys("action", "purpose", "contactHandle", "changes")
                val changes = arguments.requiredObject("changes")
                if (changes.isEmpty()) invalid()
                changes.requireAllowedKeys(
                    "displayName",
                    "phones",
                    "emails",
                    "organization",
                )
                ContactsToolRequest.UpdateContact(
                    purpose = purpose,
                    contactHandle = arguments.requiredContactHandle("contactHandle"),
                    changes = ContactChanges(
                        displayName = changes.optionalString(
                            "displayName",
                            MAX_DISPLAY_NAME_CHARS,
                        ),
                        phones = changes.optionalValues("phones", MAX_PHONE_CHARS),
                        emails = changes.optionalValues("emails", MAX_EMAIL_CHARS),
                        organization = changes.organizationChange("organization"),
                    ),
                )
            }
            ContactsToolAction.DELETE_CONTACT -> {
                arguments.requireExactKeys("action", "purpose", "contactHandle")
                ContactsToolRequest.DeleteContact(
                    purpose = purpose,
                    contactHandle = arguments.requiredContactHandle("contactHandle"),
                )
            }
        }
    }

    private fun JsonObject.requiredString(name: String, maximum: Int): String {
        val primitive = this[name] as? JsonPrimitive ?: invalid()
        if (!primitive.isString) invalid()
        return primitive.content.trim().takeIf { it.isNotEmpty() && it.length <= maximum }
            ?: invalid()
    }

    private fun JsonObject.nullableString(name: String, maximum: Int): String? {
        val value = this[name] ?: invalid()
        if (value === JsonNull) return null
        return requiredString(name, maximum)
    }

    private fun JsonObject.optionalString(name: String, maximum: Int): String? =
        if (name in this) requiredString(name, maximum) else null

    private fun JsonObject.requiredObject(name: String): JsonObject =
        this[name] as? JsonObject ?: invalid()

    private fun JsonObject.requiredContactHandle(name: String): String =
        requiredString(name, 160).also {
            if (!CONTACT_HANDLE.matches(it)) invalid()
        }

    private fun JsonObject.requiredValues(
        name: String,
        maximumValueChars: Int,
    ): List<ContactValue> {
        val array = this[name] as? JsonArray ?: invalid()
        if (array.size > MAX_VALUES_PER_KIND) invalid()
        val values = array.map { element ->
            val value = element as? JsonObject ?: invalid()
            value.requireExactKeys("value", "label", "primary")
            val primary = (value["primary"] as? JsonPrimitive)
                ?.booleanOrNull
                ?: invalid()
            ContactValue(
                value = value.requiredString(
                    "value",
                    maximumValueChars,
                ),
                label = value.requiredString("label", MAX_LABEL_CHARS),
                primary = primary,
            )
        }
        if (values.count(ContactValue::primary) > 1) invalid()
        val normalized = values.map(ContactValue::value).map(String::lowercase)
        if (normalized.size != normalized.toSet().size) invalid()
        return values
    }

    private fun JsonObject.optionalValues(
        name: String,
        maximumValueChars: Int,
    ): List<ContactValue>? =
        if (name in this) requiredValues(name, maximumValueChars) else null

    private fun JsonObject.nullableOrganization(name: String): ContactOrganization? {
        val value = this[name] ?: invalid()
        if (value === JsonNull) return null
        return (value as? JsonObject)?.toOrganization() ?: invalid()
    }

    private fun JsonObject.toOrganization(): ContactOrganization {
        requireExactKeys("company", "title")
        val company = nullableString("company", MAX_ORGANIZATION_CHARS)
        val title = nullableString("title", MAX_TITLE_CHARS)
        if (company == null && title == null) invalid()
        return ContactOrganization(company, title)
    }

    private fun JsonObject.organizationChange(
        name: String,
    ): ContactNullableChange<ContactOrganization> {
        val value: JsonElement = this[name] ?: return ContactNullableChange.Unchanged
        return if (value === JsonNull) {
            ContactNullableChange.Clear
        } else {
            ContactNullableChange.Set((value as? JsonObject)?.toOrganization() ?: invalid())
        }
    }

    private fun JsonObject.requireExactKeys(vararg allowed: String) {
        if (keys != allowed.toSet()) invalid()
    }

    private fun JsonObject.requireAllowedKeys(vararg allowed: String) {
        if (!allowed.toSet().containsAll(keys)) invalid()
    }

    private fun invalid(): Nothing = throw ContactsToolArgumentsException()
}
