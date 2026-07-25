package app.momoding.ui.icons

import androidx.compose.ui.graphics.vector.ImageVector
import app.momoding.ui.icons.phosphor.fill.GearSix as FilledGearSix
import app.momoding.ui.icons.phosphor.fill.ListChecks as FilledListChecks
import app.momoding.ui.icons.phosphor.fill.PaperPlaneTilt as FilledPaperPlaneTilt
import app.momoding.ui.icons.phosphor.fill.Stop as FilledStop
import app.momoding.ui.icons.phosphor.regular.ArrowClockwise
import app.momoding.ui.icons.phosphor.regular.ArrowLeft
import app.momoding.ui.icons.phosphor.regular.Code
import app.momoding.ui.icons.phosphor.regular.DotsThree
import app.momoding.ui.icons.phosphor.regular.FileText
import app.momoding.ui.icons.phosphor.regular.FolderSimple
import app.momoding.ui.icons.phosphor.regular.GearSix
import app.momoding.ui.icons.phosphor.regular.ListChecks
import app.momoding.ui.icons.phosphor.regular.MagnifyingGlass
import app.momoding.ui.icons.phosphor.regular.PaperPlaneTilt
import app.momoding.ui.icons.phosphor.regular.Plus
import app.momoding.ui.icons.phosphor.regular.ShieldCheck
import app.momoding.ui.icons.phosphor.regular.Stop
import app.momoding.ui.icons.phosphor.regular.TerminalWindow
import app.momoding.ui.icons.phosphor.regular.Warning
import app.momoding.ui.icons.phosphor.regular.X

/**
 * The audited Momoding functional icon vocabulary.
 *
 * These vectors are the selected Phosphor 2.1 Regular glyphs from the visual gate. Keep this
 * object small: adding an icon requires a real 20/24dp comparison, a semantic mapping, and an
 * update to the attribution notice. Brand marks do not belong in this family.
 */
object MomodingIcons {
    val Back: ImageVector get() = PhosphorRegular.ArrowLeft
    val Close: ImageVector get() = PhosphorRegular.X
    val More: ImageVector get() = PhosphorRegular.DotsThree
    val Search: ImageVector get() = PhosphorRegular.MagnifyingGlass
    val Add: ImageVector get() = PhosphorRegular.Plus
    val Send: ImageVector get() = PhosphorRegular.PaperPlaneTilt
    val Stop: ImageVector get() = PhosphorRegular.Stop
    val Retry: ImageVector get() = PhosphorRegular.ArrowClockwise
    val Tasks: ImageVector get() = PhosphorRegular.ListChecks
    val Settings: ImageVector get() = PhosphorRegular.GearSix
    val File: ImageVector get() = PhosphorRegular.FileText
    val Folder: ImageVector get() = PhosphorRegular.FolderSimple
    val Terminal: ImageVector get() = PhosphorRegular.TerminalWindow
    val Code: ImageVector get() = PhosphorRegular.Code
    val Warning: ImageVector get() = PhosphorRegular.Warning
    val Permission: ImageVector get() = PhosphorRegular.ShieldCheck

    val auditedRegular: List<ImageVector>
        get() = listOf(
            Back,
            Close,
            More,
            Search,
            Add,
            Send,
            Stop,
            Retry,
            Tasks,
            Settings,
            File,
            Folder,
            Terminal,
            Code,
            Warning,
            Permission,
        )
}

/** Fill is reserved for selected navigation and the active send/stop control. */
object MomodingFilledIcons {
    val Tasks: ImageVector get() = PhosphorFill.FilledListChecks
    val Settings: ImageVector get() = PhosphorFill.FilledGearSix
    val Send: ImageVector get() = PhosphorFill.FilledPaperPlaneTilt
    val Stop: ImageVector get() = PhosphorFill.FilledStop
}

object PhosphorRegular

object PhosphorFill
