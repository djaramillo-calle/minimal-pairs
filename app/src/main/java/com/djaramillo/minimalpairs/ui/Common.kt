package com.djaramillo.minimalpairs.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.djaramillo.minimalpairs.domain.Ipa
import java.util.Locale
import kotlin.math.roundToInt

/** `0.775` → `78 %`; null → `—`. */
fun pctText(v: Double?): String = if (v == null) "—" else "${(v * 100).roundToInt()} %"

fun ratioText(v: Double): String = String.format(Locale.UK, "%.2f", v)

fun bytesText(bytes: Long): String = when {
    bytes >= 1_000_000L -> String.format(Locale.UK, "%.1f MB", bytes / 1e6)
    bytes >= 1_000L -> String.format(Locale.UK, "%.0f kB", bytes / 1e3)
    else -> "$bytes B"
}

/** IPA with the differing phoneme in colour + bold. */
@Composable
fun ipaAnnotated(h: Ipa.Highlight, highlight: androidx.compose.ui.graphics.Color): AnnotatedString =
    buildAnnotatedString {
        append("/")
        append(h.before)
        if (h.range != null) {
            withStyle(SpanStyle(color = highlight, fontWeight = FontWeight.Bold)) { append(h.highlighted) }
            append(h.after)
        }
        append("/")
    }

@Composable
fun SectionCard(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            androidx.compose.foundation.layout.Spacer(Modifier.padding(4.dp))
            content()
        }
    }
}
