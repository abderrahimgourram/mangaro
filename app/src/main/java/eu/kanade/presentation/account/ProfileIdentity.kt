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
        RankEmblem(level,Modifier.align(Alignment.BottomEnd).padding(end=18.dp,bottom=12.dp).size(68.dp),animated=true)
    }
}

@Composable
internal fun DeveloperBadge(role: AccountRole, compact: Boolean = false) {
    if (role != AccountRole.DEVELOPER) return
    val style = RankVisuals.resolve(30)
    Row(Modifier.background(Color(style.surface),RoundedCornerShape(7.dp))
        .border(.5.dp,Color(style.secondary).copy(alpha=.55f),RoundedCornerShape(7.dp))
        .padding(horizontal=7.dp,vertical=3.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)) {
        Text("M",color=Color(style.secondary),fontWeight=FontWeight.Bold,style=MaterialTheme.typography.labelSmall)
        Text(if(compact) "مطور" else "مطور Mangaro",color=Color(style.primary),style=MaterialTheme.typography.labelSmall)
    }
}

@Composable
internal fun RankIdentity(level: Int, prominent: Boolean = false) {
    val style = RankVisuals.resolve(level)
    FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp),verticalArrangement=Arrangement.spacedBy(4.dp),itemVerticalAlignment=Alignment.CenterVertically) {
        RankEmblem(level,Modifier.size(if(prominent) 52.dp else 22.dp),animated=prominent)
        Text(MangaroRanks.titleFor(level),color=Color(style.primary),style=if(prominent) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelSmall)
        Row(Modifier.background(Color(style.surface),RoundedCornerShape(7.dp))
            .border(.5.dp,Color(style.primary).copy(alpha=.5f),RoundedCornerShape(7.dp))
            .padding(horizontal=7.dp,vertical=3.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(if(style.isMax) "Lv.$level · MAX" else "Lv.$level",color=Color(style.primary),fontWeight=FontWeight.SemiBold,
                style=MaterialTheme.typography.labelSmall.copy(textDirection=TextDirection.Ltr))
        }
    }
}
