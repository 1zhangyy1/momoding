package app.momoding.ui

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Momoding's design rules in one place (阶段UI-目标.md 设计规范, 阶段UI2-统一.md): every
 * component takes its type sizes, corner radii and spacing from here, so the same kind of
 * thing looks the same on every page. Colours are in [Tones].
 */
object Design {
    /** Type sizes, each with its line height in [Line]. */
    object Size {
        val Display = 22.sp // a whole page's headline: first run, recovery
        val Title = 17.sp // a card's or a page's name
        val Body = 15.sp // bubbles, card text, options, buttons
        val Label = 14.sp // pill buttons: next steps, links
        val Note = 13.sp // secondary text inside cards, step titles
        val Caption = 12.sp // quiet lines, subtitles, hints
        val Micro = 11.sp // file-type tiles, badges
    }

    object Line {
        val Display = 30.sp
        val Title = 22.sp
        val Body = 22.sp
        val Label = 20.sp
        val Note = 19.sp
        val Caption = 16.sp
        val Micro = 14.sp
    }

    object Radius {
        val Bubble = 18.dp
        val Tail = 6.dp // a turn's last bubble, the corner nearest the speaker
        val Card = 20.dp // waiting on the person: draft, permission, handoff, approval
        val Panel = 16.dp // quiet cards, work cards
        val Row = 12.dp // option rows and tiles inside cards
        val Press = 8.dp // small pressable areas (quiet lines)
    }

    object Space {
        val Gutter = 14.dp // inside a card
        val Gap = 8.dp // between things in one group
        val Same = 4.dp // between two turns of the same speaker
        val Switch = 12.dp // when the speaker changes
    }
}
