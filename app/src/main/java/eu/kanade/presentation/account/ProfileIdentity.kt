package eu.kanade.presentation.account

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import mihon.domain.account.*
import tachiyomi.core.common.util.lang.WesternDigits

internal fun rankAccent(level: Int) = Color(RankVisuals.resolve(level).primary)

/** Shared offline relic for the existing effective visual tier. */
@Composable
internal fun RankEmblem(level: Int, modifier: Modifier = Modifier, animated: Boolean = false) {
    RankArtwork(level, modifier, animated)
}

@Composable
internal fun TierAvatarFrame(level: Int, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val style = RankVisuals.resolve(level)
    val shape = RoundedCornerShape(24)
    Box(modifier.clip(shape).background(Color(style.surface))
        .border(if (style.isMax) 1.5.dp else 1.dp, Brush.linearGradient(listOf(Color(style.primary).copy(alpha=.85f),Color(style.secondary).copy(alpha=.65f))),shape)
        .padding(4.dp),contentAlignment=Alignment.Center) {
        content()
        if (style.isMax) Box(Modifier.align(Alignment.BottomEnd).size(11.dp).clip(RoundedCornerShape(3.dp)).background(Color(style.surface))
            .border(.5.dp,Color(style.secondary),RoundedCornerShape(3.dp)),contentAlignment=Alignment.Center) {
            Canvas(Modifier.size(6.dp).clearAndSetSemantics {}) {
                val diamond=Path().apply {moveTo(size.width/2,0f);lineTo(size.width,size.height/2);lineTo(size.width/2,size.height);lineTo(0f,size.height/2);close()}
                drawPath(diamond,Color(style.primary))
            }
        }
    }
}

@Composable
internal fun RankCoverAccent(level: Int, modifier: Modifier = Modifier) {
    val style = RankVisuals.resolve(level)
    Box(modifier.clearAndSetSemantics {}) {
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(72.dp)
            .background(Brush.verticalGradient(listOf(Color.Transparent,Color(style.surface).copy(alpha=.94f)))))
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(if(style.isMax) 3.dp else 2.dp)
            .background(Brush.horizontalGradient(listOf(Color(style.primary).copy(alpha=.5f),Color(style.secondary).copy(alpha=.8f)))))
    }
}

@Composable
internal fun DeveloperBadge(role: AccountRole, compact: Boolean = false) {
    if (role != AccountRole.DEVELOPER) return
    val style = RankVisuals.resolve(30)
    Row(Modifier.background(Color(style.secondary).copy(alpha = .08f),RoundedCornerShape(8.dp))
        .border(.5.dp,Color(style.secondary).copy(alpha=.22f),RoundedCornerShape(8.dp))
        .padding(horizontal=7.dp,vertical=3.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)) {
        Text(if(compact) "المطور" else "مطور Mangaro",color=Color(style.secondary),style=MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium)
    }
}

@Composable
internal fun RankIdentity(level: Int, prominent: Boolean = false, compact: Boolean = false) {
    val style = RankVisuals.resolve(level)
    if (prominent) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RankEmblem(level, Modifier.size(48.dp), animated = true)
            Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(MangaroRanks.titleFor(level), color = Color(style.primary), style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold)
                Text("المستوى ${WesternDigits.isolate(level.toString())}" + if (style.isMax) " · المستوى الأقصى" else "",
                    color = Color(0xFFB7A9C4), style = MaterialTheme.typography.labelSmall)
            }
        }
        return
    }
    if (compact) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
            itemVerticalAlignment = Alignment.CenterVertically) {
            RankEmblem(level, Modifier.size(20.dp))
            Text(MangaroRanks.titleFor(level), color = Color(style.primary), style = MaterialTheme.typography.labelSmall)
            Text("· ${WesternDigits.isolate("Lv.$level")}", color = Color(0xFFAF9CBE), style = MaterialTheme.typography.labelSmall)
        }
        return
    }
    FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp),verticalArrangement=Arrangement.spacedBy(4.dp),itemVerticalAlignment=Alignment.CenterVertically) {
        RankEmblem(level,Modifier.size(22.dp))
        Text(MangaroRanks.titleFor(level),color=Color(style.primary),style=MaterialTheme.typography.labelSmall)
        Row(Modifier.background(Color(style.surface),RoundedCornerShape(7.dp))
            .border(.5.dp,Color(style.primary).copy(alpha=.5f),RoundedCornerShape(7.dp))
            .padding(horizontal=7.dp,vertical=3.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(if(style.isMax) "Lv.$level · MAX" else "Lv.$level",color=Color(style.primary),fontWeight=FontWeight.SemiBold,
                style=MaterialTheme.typography.labelSmall.copy(textDirection=TextDirection.Ltr))
        }
    }
}
