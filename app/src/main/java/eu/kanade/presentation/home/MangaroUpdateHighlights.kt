package eu.kanade.presentation.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Tune
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.theme.MangaroDesignSystem
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.R

/** Upgrade-only local highlights for v2.0.1. No network, account, or ad dependency. */
@Composable
fun MangaroUpdateHighlights(onContinue: () -> Unit) {
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
                            )
                        )
                    )
                    .safeDrawingPadding()
            ) {
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 22.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Image(painterResource(R.drawable.ic_splash_logo), null, Modifier.size(52.dp), contentScale = ContentScale.Fit)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "تم إصلاح المشاكل",
                                color = Color(0xFFEEE3F5),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "Mangaro ${BuildConfig.VERSION_NAME}",
                                color = MangaroDesignSystem.GoldPrimary,
                                style = MaterialTheme.typography.labelMedium.copy(textDirection = TextDirection.Ltr)
                            )
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "في تحديث Mangaro 2.0.1، أصلحنا مجموعة من المشاكل وحسّنا تجربة القراءة لتكون أكثر استقرارًا وراحة.",
                            color = Color(0xFFBEABCD),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Highlight(
                            Icons.Outlined.Book,
                            "قراءة الروايات",
                            "- تحسين عرض الفصول وإزالة النصوص غير المرغوبة.\n- تحسين استقرار التنقل بين الفصول."
                        )
                        Highlight(
                            Icons.Outlined.CollectionsBookmark,
                            "المكتبة والحساب",
                            "- تحسين المزامنة وحفظ تقدّم القراءة.\n- دعم عرض الروايات في الملف الشخصي مع عدد فصولها."
                        )
                        Highlight(
                            Icons.Outlined.NotificationsActive,
                            "الإشعارات والتحميلات",
                            "- تحسين إشعارات تحميل الفصول.\n- تقليل تكرار إشعارات الأعمال الجديدة."
                        )
                        Highlight(
                            Icons.Outlined.Tune,
                            "تحسينات أخرى",
                            "- إصلاحات وتحسينات عامة لاستقرار التطبيق."
                        )
                    }
                }
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onContinue,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 54.dp),
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MangaroDesignSystem.GoldPrimary,
                            contentColor = MangaroDesignSystem.BackgroundDark
                        )
                    ) {
                        Text("متابعة القراءة", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        "شكرًا لاستخدامك Mangaro 💜",
                        color = Color(0xFFB7A9C4),
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center
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
            verticalAlignment = Alignment.Top
        ) {
            Box(
                Modifier.size(40.dp).background(Color(0xFF342440), RoundedCornerShape(13.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, Modifier.size(21.dp), tint = Color(0xFFE1C487))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(title, color = Color(0xFFF0E6F7), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(description, color = Color(0xFFB9A7C8), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
