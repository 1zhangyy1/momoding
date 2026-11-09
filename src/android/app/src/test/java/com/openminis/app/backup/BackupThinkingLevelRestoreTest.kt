package com.openminis.app.backup

import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.model.ThinkingLevel
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-restore-thinking-level] A group restored from a backup kept its
 * members but lost its thinking level, and so did per-model ceilings and sub
 * agent overrides.
 *
 * Root cause: both platforms define the same seven levels, but iOS writes them
 * as its `String` raw values (`"high"`) while Android's enum serialized by
 * NAME (`"HIGH"`). `BackupFormat.json` runs with `coerceInputValues = true`,
 * which turns an enum string it does not recognize on a nullable property into
 * `null` — silently, with no exception and no log. Every iOS-written level
 * therefore restored as "no level".
 */
class BackupThinkingLevelRestoreTest {

    /** provider_config.json as iOS writes it: lowercase raw values. */
    private val iosProviderConfig = """
        {
          "instances": [],
          "modelEntries": [{
            "providerInstanceId": "p1",
            "model": {"id": "gemini-3-pro", "displayName": "Gemini 3 Pro", "provider": "antigravity"},
            "overrides": {"maxThinkingLevel": "max"},
            "uuid": "e1"
          }],
          "modelGroups": [{
            "id": "g1", "name": "Group", "memberEntryIds": ["e1"],
            "strategy": "fallback", "fallbackStrategy": "default",
            "defaultThinkingLevel": "high"
          }]
        }
    """.trimIndent()

    @Test
    fun iosLowercaseLevels_surviveProviderConfigRestore() {
        val config = BackupImporter.parseProviderConfigLeniently(iosProviderConfig).config
        assertEquals(ThinkingLevel.HIGH, config.modelGroups.single().defaultThinkingLevel)
        assertEquals(ThinkingLevel.MAX, config.modelEntries.single().overrides.maxThinkingLevel)
    }

    @Test
    fun androidBackup_roundTripsItsOwnLevels() {
        val group = ModelGroup(id = "g1", name = "G", defaultThinkingLevel = ThinkingLevel.ULTRA)
        val text = BackupFormat.json.encodeToString(
            ProviderConfig.serializer(), ProviderConfig(modelGroups = mutableListOf(group)),
        )
        val back = BackupImporter.parseProviderConfigLeniently(text).config
        assertEquals(ThinkingLevel.ULTRA, back.modelGroups.single().defaultThinkingLevel)
    }

    @Test
    fun androidEncoding_isUnchanged() {
        // The level is also persisted in Room blobs (ModelOverrides)
        // and the ProviderConfig JSON mirror. Changing what
        // is WRITTEN would make an older build (a downgrade) misread them, so
        // the fix is read-side only: the written form stays the enum name.
        val text = BackupFormat.json.encodeToString(
            ModelGroup.serializer(),
            ModelGroup(id = "g1", name = "G", defaultThinkingLevel = ThinkingLevel.HIGH),
        )
        assertTrue(text, text.contains("\"defaultThinkingLevel\":\"HIGH\""))
    }

    @Test
    fun absentLevel_staysNull() {
        val wire = """{"id":"g1","name":"G","memberEntryIds":[]}"""
        assertNull(BackupFormat.json.decodeFromString(ModelGroup.serializer(), wire).defaultThinkingLevel)
    }

}
