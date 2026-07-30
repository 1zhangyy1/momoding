package app.momoding.core.contacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactsHandleRegistryTest {
    @Test
    fun `contact and page handles are opaque task scoped and explicitly invalidated`() {
        val registry = ContactsHandleRegistry()
        val target = ContactTarget(contactId = 42, lookupKey = "provider-lookup-key")
        val fingerprint = registry.queryFingerprint(" Alex ")
        val contact = registry.bindContact("task-a", target)
        val cursor = registry.bindCursor("task-a", fingerprint, 10)

        assertTrue(contact.matches(Regex("^contact-[0-9a-f]{24}$")))
        assertTrue(cursor.matches(Regex("^contacts-page-[0-9a-f]{24}$")))
        assertEquals(target, registry.contact("task-a", contact))
        assertEquals(contact, registry.bindContact("task-a", target))
        assertEquals(10, registry.cursor("task-a", cursor, fingerprint))
        assertNull(registry.contact("task-b", contact))
        assertNull(registry.cursor("task-a", cursor, registry.queryFingerprint("Other")))
        assertEquals(fingerprint, registry.queryFingerprint("alex"))

        registry.clearTask("task-a")

        assertNull(registry.contact("task-a", contact))
        assertNull(registry.cursor("task-a", cursor, fingerprint))
    }
}
