package eu.kanade.presentation.sigils

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import mihon.domain.sigils.RealmSigils
import mihon.domain.sigils.SigilDefinition
import mihon.domain.sigils.SigilRarity

@Composable
internal fun SigilRarityLabel(definition: SigilDefinition, unlocked: Boolean) {
    val count = when (definition.rarity) {
        SigilRarity.COMMON -> 1
        SigilRarity.RARE -> 2
        SigilRarity.EPIC -> 3
        SigilRarity.LEGENDARY -> 4
    }
    val color = Color(if (definition.rarity == SigilRarity.LEGENDARY) 0xFFE4C576 else definition.accent)
        .copy(alpha = if (unlocked) .9f else .45f)
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(count) { Box(Modifier.size(3.dp, 3.dp).background(color, RoundedCornerShape(1.dp))) }
        }
        Text(definition.rarity.title, color = color, style = MaterialTheme.typography.labelSmall)
    }
}

/** Credits are available offline; links are voluntary source/license references. */
@Composable
internal fun SigilArtCredits(onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF15111E),
        title = { Text("فن الأختام وتراخيصه") },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Text("نقوش Lorc وDelapouite من Game-icons.net بترخيص CC BY 3.0. أعاد Mangaro تصميمها بإطارات أصلية، وترصيع ونقوش معدنية وألوان العوالم. تبقى الرسوم المشتقة متاحة بهذا الترخيص.", color = Color(0xFFD4C5E0), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Start)
                }
                item {
                    TextButton(onClick = { uriHandler.openUri("https://creativecommons.org/licenses/by/3.0/") }) { Text("ترخيص CC BY 3.0") }
                    TextButton(onClick = { uriHandler.openUri("http://lorcblog.blogspot.com") }) { Text("Lorc") }
                    TextButton(onClick = { uriHandler.openUri("https://delapouite.com") }) { Text("Delapouite") }
                }
                items(RealmSigils.all, key = { it.id }) { definition ->
                    val art = SigilArtAssets.forId(definition.id)
                    Column {
                        Text(definition.name, color = Color(definition.accent), style = MaterialTheme.typography.titleSmall)
                        TextButton(onClick = { uriHandler.openUri(art.sourceUrl) }) { Text("${art.author} · الأصل والترخيص", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("إغلاق") } },
    )
}
