package app.momoding.core.contacts

import app.momoding.core.runtime.local.PiNativeToolRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactsToolRequestParserTest {
    @Test
    fun `search and get contact use two exact easy to select actions`() {
        val search = ContactsToolRequestParser.parse(
            buildJsonObject {
                put("action", "search")
                put("purpose", "Find Alex")
                put("query", "Alex")
                put("cursor", JsonNull)
            },
        ) as ContactsToolRequest.Search
        val get = ContactsToolRequestParser.parse(
            buildJsonObject {
                put("action", "get_contact")
                put("purpose", "Read the selected contact")
                put("contactHandle", CONTACT_HANDLE)
            },
        ) as ContactsToolRequest.GetContact

        assertEquals("Alex", search.query)
        assertNull(search.cursor)
        assertEquals(CONTACT_HANDLE, get.contactHandle)
        assertEquals(
            listOf(ContactsToolAction.SEARCH, ContactsToolAction.GET_CONTACT),
            listOf(search.action, get.action),
        )
    }

    @Test
    fun `parser rejects missing fields provider ids and extra arguments`() {
        val invalid = listOf(
            buildJsonObject {
                put("action", "search")
                put("purpose", "Find Alex")
                put("query", "Alex")
            },
            buildJsonObject {
                put("action", "search")
                put("purpose", "Find Alex")
                put("query", "Alex")
                put("cursor", JsonNull)
                put("limit", 1)
            },
            buildJsonObject {
                put("action", "get_contact")
                put("purpose", "Read a contact")
                put("contactHandle", "content://com.android.contacts/contacts/42")
            },
            buildJsonObject {
                put("action", "get_contact")
                put("purpose", "Read a contact")
                put("contactHandle", CONTACT_HANDLE)
                put("contactId", 42)
            },
            createArguments().let { arguments ->
                buildJsonObject {
                    arguments.forEach { (key, value) -> put(key, value) }
                    put("phones", buildJsonArray {
                        repeat(2) {
                            add(contactValue("+1", primary = true))
                        }
                    })
                }
            },
            buildJsonObject {
                put("action", "update_contact")
                put("purpose", "Change a contact")
                put("contactHandle", CONTACT_HANDLE)
                put("changes", buildJsonObject {})
            },
        )

        invalid.forEach { arguments ->
            assertThrows(ContactsToolArgumentsException::class.java) {
                ContactsToolRequestParser.parse(arguments)
            }
        }
    }

    @Test
    fun `create update and delete expose exact bounded mutation shapes`() {
        val create = ContactsToolRequestParser.parse(
            createArguments(),
        ) as ContactsToolRequest.CreateContact
        val update = ContactsToolRequestParser.parse(
            buildJsonObject {
                put("action", "update_contact")
                put("purpose", "Change Alex's work email")
                put("contactHandle", CONTACT_HANDLE)
                put(
                    "changes",
                    buildJsonObject {
                        put("emails", buildJsonArray {
                            add(contactValue("alex@work.example", "Work", true))
                        })
                        put("organization", JsonNull)
                    },
                )
            },
        ) as ContactsToolRequest.UpdateContact
        val delete = ContactsToolRequestParser.parse(
            buildJsonObject {
                put("action", "delete_contact")
                put("purpose", "Delete the duplicate contact")
                put("contactHandle", CONTACT_HANDLE)
            },
        ) as ContactsToolRequest.DeleteContact

        assertEquals("Alex Chen", create.write.displayName)
        assertEquals(1, create.write.phones.size)
        assertEquals(1, update.changes.emails?.size)
        assertTrue(update.changes.organization is ContactNullableChange.Clear)
        assertEquals(CONTACT_HANDLE, delete.contactHandle)
    }

    @Test
    fun `contract executor returns compact redacted errors`() = runTest {
        val secret = "SENSITIVE_CONTACT_QUERY_MUST_NOT_BE_ECHOED"
        val executor = PhoneLocalContactsToolExecutor()
        val result = executor.execute(
            "task-contacts",
            PiNativeToolRequest(
                id = "native-contacts-invalid",
                kind = "android_contacts_tool",
                toolCallId = "pi-contacts-invalid",
                toolName = PhoneLocalContactsToolExecutor.TOOL_NAME,
                arguments = buildJsonObject {
                    put("action", "search")
                    put("purpose", "Find a contact")
                    put("query", secret)
                    put("unexpected", true)
                },
            ),
        )

        assertTrue(result.isError)
        assertEquals(
            "INVALID_ARGUMENTS",
            result.contentPayload.getValue("error").jsonObject
                .getValue("code").jsonPrimitive.content,
        )
        assertEquals("search", result.contentPayload.getValue("action").jsonPrimitive.content)
        assertFalse(result.contentPayload.toString().contains(secret))
    }

    private companion object {
        const val CONTACT_HANDLE = "contact-111111111111111111111111"

        fun contactValue(
            value: String,
            label: String = "Mobile",
            primary: Boolean,
        ) = buildJsonObject {
            put("value", value)
            put("label", label)
            put("primary", primary)
        }

        fun createArguments() = buildJsonObject {
            put("action", "create_contact")
            put("purpose", "Create Alex")
            put("displayName", "Alex Chen")
            put("phones", buildJsonArray {
                add(contactValue("+8613800000000", primary = true))
            })
            put("emails", buildJsonArray {
                add(contactValue("alex@example.test", "Work", true))
            })
            put(
                "organization",
                buildJsonObject {
                    put("company", "Example")
                    put("title", "Engineer")
                },
            )
        }
    }
}
