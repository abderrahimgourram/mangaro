package eu.kanade.presentation.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.theme.MangaroDesignSystem
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.updater.ReleaseNotes

/** First-launch release notes; account state and remote mandatory updates are separate. */
@Composable
fun MangaroUpdateHighlights(onContinue: () -> Unit) {
    val context = LocalContext.current
    val notes = remember {
        ReleaseNotes.catalog(context.assets.open("release-notes.json").bufferedReader().use { it.readText() })
            .filter { it.versionCode <= BuildConfig.VERSION_CODE }
    }
    BackHandler(onBack = onContinue)
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Surface(modifier = Modifier.fillMaxSize(), color = MangaroDesignSystem.BackgroundDark) {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color(0xFF24162F),
                                MangaroDesignSystem.BackgroundDark,
                                MangaroDesignSystem.BackgroundDark,
                            ),
                        ),
                    )
                    .safeDrawingPadding(),
            ) {
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 22.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Image(
                            painterResource(R.drawable.ic_splash_logo),
                            null,
                            Modifier.size(52.dp),
                            contentScale = ContentScale.Fit,
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "الجديد في Mangaro ${BuildConfig.VERSION_NAME}",
                                color = Color(0xFFEEE3F5),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                "الإصدار ${BuildConfig.VERSION_NAME}",
                                color = MangaroDesignSystem.GoldPrimary,
                                style = MaterialTheme.typography.labelMedium.copy(textDirection = TextDirection.Ltr),
                            )
                        }
                    }
                    notes.forEach { release ->
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(release.title, color = Color(0xFFEEE3F5), style = MaterialTheme.typography.titleMedium)
                            release.items.forEach { item ->
                                Highlight(Icons.Outlined.Tune, item, "")
                            }
                        }
                    }
                }
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = onContinue,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 54.dp),
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MangaroDesignSystem.GoldPrimary,
                            contentColor = MangaroDesignSystem.BackgroundDark,
                        ),
                    ) {
                        Text("ابدأ القراءة", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        "شكرًا لاستخدامك Mangaro 💜",
                        color = Color(0xFFB7A9C4),
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun Highlight(icon: ImageVector, title: String, description: String) {
    Surface(color = Color(0xFF211829), shape = RoundedCornerShape(20.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                Modifier.size(40.dp).background(Color(0xFF342440), RoundedCornerShape(13.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, Modifier.size(21.dp), tint = Color(0xFFE1C487))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    title,
                    color = Color(0xFFF0E6F7),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                if (description.isNotEmpty()) {
                    Text(
                        description,
                        color = Color(0xFFB9A7C8),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
