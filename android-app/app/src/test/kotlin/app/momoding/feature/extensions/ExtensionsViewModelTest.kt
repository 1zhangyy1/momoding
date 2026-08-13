package app.momoding.feature.extensions

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.momoding.core.data.MomodingDatabase
import app.momoding.core.skills.PhoneLocalSkillResource
import app.momoding.core.skills.SkillAvailability
import app.momoding.core.skills.SkillCatalogService
import app.momoding.core.skills.SkillDocumentParseResult
import app.momoding.core.skills.SkillImportReader
import app.momoding.core.skills.SkillRepository
import app.momoding.core.skills.SkillRecord
import app.momoding.core.skills.SkillSource
import app.momoding.core.skills.sha256Utf8
import app.momoding.core.extensions.ExtensionPackageCatalogService
import app.momoding.core.extensions.ExtensionPackageImportReader
import app.momoding.core.extensions.ExtensionPackageManifestParser
import app.momoding.core.extensions.ExtensionPackageRepository
import app.momoding.core.extensions.ExtensionPackageAccessDiff
import app.momoding.core.extensions.ExtensionPackageInstallResult
import app.momoding.core.extensions.ExtensionPackageManifest
import app.momoding.core.extensions.ExtensionPackageRecord
import app.momoding.core.extensions.ExtensionPackageSnapshot
import app.momoding.core.extensions.ExtensionHostToolSnapshot
import app.momoding.core.extensions.ExtensionToolSnapshot
import app.momoding.core.extensions.PiExtensionCredentialBinding
import app.momoding.core.extensions.PiExtensionCredentialBindingRef
import app.momoding.core.extensions.PiExtensionCredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExtensionsViewModelTest {
    private var database: MomodingDatabase? = null

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        database?.close()
    }

    @Test
    fun `bundled Skill is seeded then can be disabled from the product state`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, MomodingDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(dispatcher.asExecutor())
            .setTransactionExecutor(dispatcher.asExecutor())
            .build()
        database = db
        val repository = SkillRepository(db, ioDispatcher = dispatcher)
        repository.seedBundledSkills(
            listOf(parsedBundled("---\nname: review-checklist\ndescription: Review\n---\nReview.")),
        )
        val catalog = SkillCatalogService(
            assets = context.assets,
            importReader = SkillImportReader(context.contentResolver),
            parser = { content -> parsedBundled(content) },
            repository = repository,
            ioDispatcher = dispatcher,
        )
        val extensionRepository = ExtensionPackageRepository(db, ioDispatcher = dispatcher)
        val credentialVault = MemoryExtensionCredentialStore()
        val viewModel = ExtensionsViewModel(
            repository,
            catalog,
            extensionRepository,
            ExtensionPackageCatalogService(
                ExtensionPackageImportReader(context.contentResolver),
                ExtensionPackageManifestParser(),
                extensionRepository,
                dispatcher,
            ),
            credentialVault,
            dispatcher,
        )

        advanceUntilIdle()

        val seeded = viewModel.state.value.skills.single()
        assertEquals("review-checklist", seeded.name)
        assertTrue(seeded.enabled)
        assertEquals(SkillAvailability.AVAILABLE, seeded.availability)

        viewModel.dispatch(ExtensionsAction.SetSkillEnabled(seeded.name, false))
        advanceUntilIdle()

        assertFalse(viewModel.state.value.skills.single().enabled)
        assertEquals(
            "review-checklist is off. Momoding will not use it in future messages.",
            viewModel.state.value.notice,
        )
        assertTrue(repository.enabledResourceSet().resources.isEmpty())
    }

    @Test
    fun `Extensions settings interaction is explicit and independently bindable`() {
        val policy = app.momoding.feature.settings.SettingsInteractionPolicy
            .fromControlBindings(mapOf("OPEN_EXTENSIONS" to "OpenExtensions"))

        assertTrue(
            policy.allows(app.momoding.feature.settings.SettingsInteraction.OPEN_EXTENSIONS),
        )
        assertFalse(
            policy.allows(app.momoding.feature.settings.SettingsInteraction.OPEN_MOBILE_FILES),
        )
    }

    @Test
    fun `Skill import notices distinguish complete folders from incomplete single files`() {
        val folder = skillRecord(
            name = "project-review",
            availability = SkillAvailability.AVAILABLE,
            packageFileCount = 3,
        )
        assertEquals(
            "project-review was added with 3 files. It is off until you turn it on.",
            buildSkillImportNotice(folder, importedFromFolder = true),
        )
        assertEquals(
            "project-review was added with 1 file. It is off until you turn it on.",
            buildSkillImportNotice(
                folder.copy(resource = folder.resource.copy(packageFileCount = 1)),
                importedFromFolder = true,
            ),
        )

        val incomplete = skillRecord(
            name = "relative-review",
            availability = SkillAvailability.UNAVAILABLE,
            packageFileCount = 1,
            diagnosticCode = "RELATIVE_DEPENDENCY_UNSUPPORTED",
        )
        assertEquals(
            "relative-review was copied, but it needs its full Skill folder before it can be used. " +
                "Remove it, then add the Skill folder.",
            buildSkillImportNotice(incomplete, importedFromFolder = false),
        )
    }

    @Test
    fun `update notice exposes every access and execution boundary change`() {
        val snapshot = ExtensionPackageSnapshot(
            schemaVersion = 2,
            id = "com.example.changed",
            name = "Changed package",
            version = "2.0.0",
            description = "Changed.",
            runtime = "pi-register-tool-v1",
            entrypoint = "dist/index.js",
            tools = emptyList(),
            requiredCapabilities = emptyList(),
            optionalCapabilities = emptyList(),
            packageDigest = "a".repeat(64),
        )
        val notice = buildUpdateNotice(
            ExtensionPackageInstallResult(
                record = ExtensionPackageRecord(
                    manifest = ExtensionPackageManifest(snapshot, "{}"),
                    enabled = false,
                    fileCount = 3,
                    createdAtMillis = 1,
                    updatedAtMillis = 2,
                ),
                wasUpdate = true,
                unchanged = false,
                accessDiff = ExtensionPackageAccessDiff(
                    addedRequiredCapabilities = listOf("calendar"),
                    removedRequiredCapabilities = listOf("contacts"),
                    addedOptionalCapabilities = listOf("contacts"),
                    removedOptionalCapabilities = listOf("calendar"),
                    addedHostTools = listOf("calendar -> device_calendar [calendar]"),
                    removedHostTools = listOf("old -> device_capabilities_get"),
                    addedOrigins = listOf("https://new.example.test"),
                    removedOrigins = listOf("https://old.example.test"),
                    addedHttpMethods = listOf("POST"),
                    removedHttpMethods = listOf("GET"),
                    addedCredentialBindings = listOf("new @ https://new.example.test (authorization_bearer)"),
                    removedCredentialBindings = listOf("old @ https://old.example.test (authorization_bearer)"),
                    runtimeChanged = true,
                ),
            ),
        )

        listOf(
            "how it runs changed", "now requires Calendar", "no longer requires Contacts",
            "may now use Contacts", "no longer optionally uses Calendar", "added phone tools",
            "removed phone tools", "can now connect to", "no longer connects to",
            "added web methods", "removed web methods", "new credential setup",
            "removed credential setup", "turned off for review", "state was cleared",
        ).forEach { expected -> assertTrue(notice.contains(expected)) }
        assertFalse(notice.contains("authorization_bearer"))
    }

    @Test
    fun `Extension review summaries put purpose access network and credentials in user language`() {
        val item = ExtensionPackageUiState(
            id = "com.example.calendar",
            name = "Calendar helper",
            version = "1.0.0",
            description = "Plans calendar work.",
            runtime = "pi-register-tool-v1",
            entrypoint = "dist/index.js",
            enabled = false,
            fileCount = 3,
            tools = listOf(ExtensionToolSnapshot(
                type = "pi-register-tool",
                name = "find_time",
                description = "Finds open time for a meeting.",
                label = "Find meeting time",
            )),
            requiredCapabilities = listOf("calendar"),
            optionalCapabilities = listOf("contacts"),
            networkOrigins = listOf("https://calendar.example.test"),
            hostTools = listOf(ExtensionHostToolSnapshot(
                name = "read_calendar",
                targetTool = "device_calendar",
                capability = "calendar",
            )),
            httpPolicy = app.momoding.core.extensions.PiRegisterToolHttpPolicy(
                origins = listOf("https://calendar.example.test"),
                methods = listOf("GET", "POST"),
            ),
            packageDigest = "a".repeat(64),
            activationAvailable = true,
        )

        assertEquals(
            "Find meeting time — Finds open time for a meeting.",
            extensionPurposeSummary(item),
        )
        assertEquals(
            "Required: Calendar\nOptional: Contacts\nTools: Calendar",
            extensionPhoneAccessSummary(item),
        )
        assertEquals(
            "https://calendar.example.test · GET, POST",
            extensionInternetAccessSummary(item),
        )

        val noAccess = item.copy(
            requiredCapabilities = emptyList(),
            optionalCapabilities = emptyList(),
            networkOrigins = emptyList(),
            hostTools = emptyList(),
            httpPolicy = app.momoding.core.extensions.PiRegisterToolHttpPolicy(),
        )
        assertEquals("No phone access declared", extensionPhoneAccessSummary(noAccess))
        assertEquals("No internet access", extensionInternetAccessSummary(noAccess))

        val legacyAlias = noAccess.copy(
            runtime = "declarative-v1",
            tools = listOf(ExtensionToolSnapshot(
                type = "android-tool-alias",
                name = "read_clipboard",
                description = "Reads clipboard text.",
                targetTool = "device_clipboard",
            )),
        )
        assertEquals("Tools: Clipboard", extensionPhoneAccessSummary(legacyAlias))
        assertEquals("All files in shared storage", extensionAccessLabel("all_files"))
    }

    @Test
    fun `post-commit cleanup failure is detected from the authoritative installed digest`() {
        val previousDigest = "a".repeat(64)
        val currentDigest = "b".repeat(64)
        val current = ExtensionPackageRecord(
            manifest = ExtensionPackageManifest(
                ExtensionPackageSnapshot(
                    schemaVersion = 1,
                    id = "com.example.updated",
                    name = "Updated",
                    version = "2.0.0",
                    description = "Updated package.",
                    runtime = "declarative-v1",
                    tools = emptyList(),
                    requiredCapabilities = emptyList(),
                    optionalCapabilities = emptyList(),
                    packageDigest = currentDigest,
                ),
                "{}",
            ),
            enabled = false,
            fileCount = 2,
            createdAtMillis = 1,
            updatedAtMillis = 2,
        )

        assertEquals(
            ExtensionImportFailureOutcome.Committed(current),
            extensionImportFailureOutcome(
                beforeDigests = mapOf(current.manifest.snapshot.id to previousDigest),
                afterRecords = Result.success(listOf(current)),
            ),
        )
        assertEquals(
            ExtensionImportFailureOutcome.NotCommitted,
            extensionImportFailureOutcome(
                beforeDigests = mapOf(current.manifest.snapshot.id to currentDigest),
                afterRecords = Result.success(listOf(current)),
            ),
        )
        assertEquals(
            ExtensionImportFailureOutcome.Unknown,
            extensionImportFailureOutcome(
                beforeDigests = mapOf(current.manifest.snapshot.id to previousDigest),
                afterRecords = Result.failure(IllegalStateException("repository read failed")),
            ),
        )
        assertEquals(
            ExtensionImportFailureOutcome.Unknown,
            extensionImportFailureOutcome(beforeDigests = null, afterRecords = null),
        )
    }

    private fun parsedBundled(content: String): SkillDocumentParseResult =
        SkillDocumentParseResult(
            resource = PhoneLocalSkillResource(
                name = "review-checklist",
                description = "Review completed work against explicit acceptance criteria and report prioritized findings.",
                content = content,
                contentSha256 = content.sha256Utf8(),
                disableModelInvocation = false,
            ),
            availability = SkillAvailability.AVAILABLE,
            diagnosticCode = null,
            diagnosticMessage = null,
        )

    private fun skillRecord(
        name: String,
        availability: SkillAvailability,
        packageFileCount: Int,
        diagnosticCode: String? = null,
    ) = SkillRecord(
        skillId = "skill-$name",
        source = SkillSource.IMPORTED,
        resource = PhoneLocalSkillResource(
            name = name,
            description = "Review a project.",
            content = "---\nname: $name\ndescription: Review a project.\n---\nReview.",
            contentSha256 = "a".repeat(64),
            disableModelInvocation = false,
            packageDigest = "b".repeat(64),
            packageFileCount = packageFileCount,
        ),
        enabled = false,
        availability = availability,
        diagnosticCode = diagnosticCode,
        diagnosticMessage = null,
        createdAtMillis = 1,
        updatedAtMillis = 1,
    )
}

private class MemoryExtensionCredentialStore : PiExtensionCredentialStore {
    private val bindings = mutableMapOf<PiExtensionCredentialBinding, CharArray>()

    override fun bind(binding: PiExtensionCredentialBinding, bearerToken: CharArray) {
        bindings.put(binding, bearerToken.copyOf())?.fill('\u0000')
    }

    override suspend fun resolveBearer(binding: PiExtensionCredentialBindingRef): CharArray? =
        bindings[PiExtensionCredentialBinding(
            binding.packageId,
            binding.packageDigest,
            binding.slot,
            binding.origin,
        )]?.copyOf()

    override fun metadata(): List<PiExtensionCredentialBinding> = bindings.keys.toList()

    override fun remove(binding: PiExtensionCredentialBinding) {
        bindings.remove(binding)?.fill('\u0000')
    }

    override fun removePackage(packageId: String) {
        bindings.keys.filter { it.packageId == packageId }.forEach(::remove)
    }
}
