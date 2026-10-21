package app.momoding.ui.chat

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.momoding.ui.Tones

/**
 * The small reaction tucked under the corner of the person's bubble, like a tapback: 👀 while
 * Momoding works on it, ✅ once that work is done. It says "got it" before any words do.
 */
@Composable
fun MessageReaction(emoji: String) {
    Row(Modifier.fillMaxWidth().padding(end = 6.dp), horizontalArrangement = Arrangement.End) {
        Crossfade(emoji, label = "reaction") { e ->
            Box(
                Modifier.offset(y = (-8).dp).size(24.dp).border(1.dp, Tones.hairline, CircleShape)
                    .background(Tones.surface, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Text(e, fontSize = 12.sp) }
        }
    }
}
