package eu.kanade.presentation.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.theme.MangaroDesignSystem
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.R

/** Upgrade-only local highlights. No network, media preparation, account or ad dependency. */
@Composable
fun MangaroUpdateHighlights(onContinue: () -> Unit) {
    BackHandler(onBack = onContinue)
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        // A surface consumes touches in empty areas so the restored screen underneath cannot be activated.
        Surface(modifier = Modifier.fillMaxSize(), color = MangaroDesignSystem.BackgroundDark) {
        Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(
            Color(0xFF24162F), MangaroDesignSystem.BackgroundDark, MangaroDesignSystem.BackgroundDark,
        ))).safeDrawingPadding()) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Image(painterResource(R.drawable.ic_splash_logo), null, Modifier.size(52.dp), contentScale = ContentScale.Fit)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("ما الجديد في مانجارو؟", color = Color(0xFFEEE3F5),
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(BuildConfig.VERSION_NAME, color = MangaroDesignSystem.GoldPrimary,
                            style = MaterialTheme.typography.labelMedium.copy(textDirection = TextDirection.Ltr))
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("مانجارو بتجربة أجمل من أي وقت", color = Color(0xFFF5EEF9),
                        style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("تفاصيل جديدة تجعل القراءة والتفاعل أكثر متعة وراحة.",
                        color = Color(0xFFBEABCD), style = MaterialTheme.typography.bodyMedium)
                }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Highlight(Icons.Outlined.PersonOutline, "ملفك الشخصي بأسلوب جديد", "تصميم أجمل لهويتك ورتبتك وأختامك.")
                    Highlight(Icons.Outlined.ChatBubbleOutline, "تفاعل أجمل مع المجتمع", "تعليقات وتقييمات أكثر وضوحًا وأناقة.")
                    Highlight(Icons.Outlined.AutoAwesome, "أختام العوالم", "30 ختمًا برسومات فانتازية ترافق رحلتك.")
                    Highlight(Icons.Outlined.Speed, "تجربة أسرع وأكثر سلاسة", "بداية أخف وعناية تلقائية بمساحة التطبيق.")
                }
            }
            Button(onClick = onContinue, modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp)
                .heightIn(min = 54.dp), shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MangaroDesignSystem.GoldPrimary,
                    contentColor = MangaroDesignSystem.BackgroundDark)) {
                Text("ابدأ القراءة", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
        }
        }
    }
}

@Composable
private fun Highlight(icon: ImageVector, title: String, description: String) {
    Surface(color = Color(0xFF211829), shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).background(Color(0xFF342440), RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(21.dp), tint = Color(0xFFE1C487))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(title, color = Color(0xFFF0E6F7), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(description, color = Color(0xFFB9A7C8), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
