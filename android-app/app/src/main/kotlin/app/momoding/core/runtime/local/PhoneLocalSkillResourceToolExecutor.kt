package app.momoding.core.runtime.local

import app.momoding.core.skills.SkillRepository
import app.momoding.core.skills.SkillResourceReadException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

interface PhoneLocalSkillResourceToolHandler {
    fun handles(toolName: String): Boolean
    suspend fun execute(taskId: String, request: PiNativeToolRequest): JsonObject
}

/** Reads only resources from a currently enabled, user-installed or bundled Skill package. */
class PhoneLocalSkillResourceToolExecutor(
    private val skills: SkillRepository,
) : PhoneLocalSkillResourceToolHandler {
    override fun handles(toolName: String): Boolean = toolName == SKILL_RESOURCE_TOOL

    override suspend fun execute(taskId: String, request: PiNativeToolRequest): JsonObject {
        if (taskId.isBlank() || request.kind != SKILL_NATIVE_KIND || !handles(request.toolName)) {
            return failure("SKILL_RESOURCE_TOOL_NOT_ALLOWED", "This Skill resource tool is not available.")
        }
        return try {
            when (request.arguments.string("action")) {
                "list" -> list(request.arguments)
                "read" -> read(request.arguments)
                else -> failure("SKILL_RESOURCE_ARGUMENTS_INVALID", "The Skill resource request is invalid.")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: SkillResourceReadException) {
            failure(error.code, publicMessage(error.code))
        } catch (_: IllegalArgumentException) {
            failure("SKILL_RESOURCE_ARGUMENTS_INVALID", "The Skill resource request is invalid.")
        } catch (_: Throwable) {
            failure("SKILL_RESOURCE_READ_FAILED", "The Skill resource could not be read on this phone.")
        }
    }

    private suspend fun list(arguments: JsonObject): JsonObject {
        if (
            arguments.keys !in setOf(
                setOf("action", "skillName", "offset", "limit"),
                setOf("action", "skillName", "prefix", "offset", "limit"),
            )
        ) {
            return failure("SKILL_RESOURCE_ARGUMENTS_INVALID", "The Skill resource request is invalid.")
        }
        val skillName = arguments.string("skillName")
            ?: return failure("SKILL_RESOURCE_ARGUMENTS_INVALID", "The Skill name is invalid.")
        val prefix = arguments.optionalString("prefix")?.let { normalizePath(skillName, it) }
        val offset = arguments.integerInt("offset")
            ?: return failure("SKILL_RESOURCE_ARGUMENTS_INVALID", "The Skill resource offset is invalid.")
        val limit = arguments.integerInt("limit")
            ?: return failure("SKILL_RESOURCE_ARGUMENTS_INVALID", "The Skill resource limit is invalid.")
        val page = skills.listEnabledResources(skillName, prefix, offset, limit)
        return buildJsonObject {
            put("ok", true)
            put("action", "list")
            put("skillName", skillName)
            put("items", JsonArray(page.items.map { entry ->
                buildJsonObject {
                    put("path", entry.path)
                    put("mimeType", entry.mimeType)
                    put("byteSize", entry.byteSize)
                    put("sha256", entry.contentSha256)
                }
            }))
            put("count", page.items.size)
            put("offset", page.offset)
            put("nextOffset", page.nextOffset)
            put("eof", page.eof)
        }
    }

    private suspend fun read(arguments: JsonObject): JsonObject {
        if (arguments.keys != setOf("action", "skillName", "path", "offset", "limit")) {
            return failure("SKILL_RESOURCE_ARGUMENTS_INVALID", "The Skill resource request is invalid.")
        }
        val skillName = arguments.string("skillName")
            ?: return failure("SKILL_RESOURCE_ARGUMENTS_INVALID", "The Skill name is invalid.")
        val path = arguments.string("path")?.let { normalizePath(skillName, it) }
            ?: return failure("SKILL_RESOURCE_ARGUMENTS_INVALID", "The Skill resource path is invalid.")
        val offset = arguments.integerLong("offset")
            ?: return failure("SKILL_RESOURCE_ARGUMENTS_INVALID", "The Skill resource offset is invalid.")
        val limit = arguments.integerInt("limit")
            ?: return failure("SKILL_RESOURCE_ARGUMENTS_INVALID", "The Skill resource limit is invalid.")
        val page = skills.readEnabledTextResource(skillName, path, offset, limit)
        return buildJsonObject {
            put("ok", true)
            put("action", "read")
            put("skillName", page.skillName)
            put("path", page.path)
            put("mimeType", page.mimeType)
            put("byteSize", page.byteSize)
            put("offset", page.offset)
            put("nextOffset", page.nextOffset)
            put("eof", page.eof)
            put("content", page.content)
        }
    }

    private fun failure(code: String, message: String): JsonObject = buildJsonObject {
        put("ok", false)
        put("errorCode", code)
        put("errorMessage", message)
    }

    private fun publicMessage(code: String): String = when (code) {
        "SKILL_NOT_FOUND" -> "That Skill is not installed."
        "SKILL_NOT_ENABLED" -> "That Skill is not enabled for this task."
        "SKILL_RESOURCE_NOT_FOUND" -> "That path is not present in the enabled Skill package."
        "SKILL_RESOURCE_BINARY" -> "This resource is binary and cannot be read as text."
        "SKILL_RESOURCE_OFFSET_INVALID" -> "The requested offset is outside a UTF-8 text boundary."
        "SKILL_RESOURCE_LIMIT_INVALID" -> "The requested Skill resource page is invalid."
        "SKILL_RESOURCE_CORRUPT" -> "This installed Skill resource failed its local integrity check."
        else -> "The Skill resource could not be read safely."
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content

    private fun JsonObject.optionalString(key: String): String? =
        if (key in this) string(key) else null

    private fun JsonObject.integerLong(key: String): Long? =
        (this[key] as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.longOrNull

    private fun JsonObject.integerInt(key: String): Int? =
        (this[key] as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.intOrNull

    private fun normalizePath(skillName: String, path: String): String {
        val virtualRoot = "$SKILL_VIRTUAL_ROOT/$skillName/"
        return when {
            path.startsWith(virtualRoot) -> path.removePrefix(virtualRoot)
            path.startsWith('/') -> throw IllegalArgumentException("SKILL_RESOURCE_PATH_INVALID")
            else -> path
        }
    }

    private companion object {
        const val SKILL_NATIVE_KIND = "android_skill_tool"
        const val SKILL_RESOURCE_TOOL = "skill_resource"
        const val SKILL_VIRTUAL_ROOT = "/mobile-skills"
    }
}
