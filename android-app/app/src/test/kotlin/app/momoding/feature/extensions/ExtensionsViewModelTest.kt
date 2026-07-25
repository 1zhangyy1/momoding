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
import app.momoding.core.skills.sha256Utf8
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
        val viewModel = ExtensionsViewModel(repository, catalog)

        advanceUntilIdle()

        val seeded = viewModel.state.value.skills.single()
        assertEquals("review-checklist", seeded.name)
        assertTrue(seeded.enabled)
        assertEquals(SkillAvailability.AVAILABLE, seeded.availability)

        viewModel.dispatch(ExtensionsAction.SetSkillEnabled(seeded.name, false))
        advanceUntilIdle()

        assertFalse(viewModel.state.value.skills.single().enabled)
        assertEquals("review-checklist disabled.", viewModel.state.value.notice)
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
}
