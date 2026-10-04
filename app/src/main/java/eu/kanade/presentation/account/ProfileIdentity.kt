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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import mihon.domain.account.*

internal fun rankAccent(level: Int) = Color(RankVisuals.resolve(level).primary)

/** A shared open-page/M motif; geometry evolves, while the canonical rank titles stay intact. */
@Composable
internal fun RankEmblem(level: Int, modifier: Modifier = Modifier) {
    val style = RankVisuals.resolve(level)
    Canvas(modifier.clearAndSetSemantics {}) {
        val ink = Color(style.primary)
        val unit = size.minDimension
        fun point(x: Float,y: Float) = Offset(x*size.width,y*size.height)
        fun line(x: Float,y: Float,a: Float,b: Float,color: Color = ink) = drawLine(color,point(x,y),point(a,b),unit*0.055f)
        val pages = Path().apply {
            moveTo(size.width*0.15f,size.height*0.25f); lineTo(size.width*0.5f,size.height*0.39f)
            lineTo(size.width*0.85f,size.height*0.25f); lineTo(size.width*0.85f,size.height*0.70f)
            lineTo(size.width*0.5f,size.height*0.85f); lineTo(size.width*0.15f,size.height*0.70f); close()
        }
        drawPath(pages,ink,style=Stroke(unit*0.055f))
        line(.5f,.39f,.5f,.85f)
        if (style.tier >= 1) { line(.20f,.80f,.5f,.94f); line(.5f,.94f,.80f,.80f) }
        if (style.tier >= 2) { line(.27f,.4f,.4f,.46f); line(.6f,.46f,.73f,.4f) }
        if (style.tier >= 3) { line(.27f,.54f,.4f,.6f); line(.6f,.6f,.73f,.54f) }
        if (style.tier >= 4) { line(.07f,.31f,.07f,.65f); line(.93f,.31f,.93f,.65f) }
        if (style.tier >= 5) { line(.35f,.17f,.5f,.09f); line(.5f,.09f,.65f,.17f) }
        if (style.isMax) {
            val violet=Color(style.secondary)
            line(.35f,.17f,.5f,.26f,violet); line(.5f,.26f,.65f,.17f,violet)
            drawCircle(violet,unit*.035f,point(.5f,.09f))
        }
    }
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
        RankEmblem(level,Modifier.align(Alignment.BottomEnd).padding(end=18.dp,bottom=12.dp).size(28.dp))
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
        RankEmblem(level,Modifier.size(if(prominent) 26.dp else 18.dp))
        Text(MangaroRanks.titleFor(level),color=Color(style.primary),style=if(prominent) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelSmall)
        Row(Modifier.background(Color(style.surface),RoundedCornerShape(7.dp))
            .border(.5.dp,Color(style.primary).copy(alpha=.5f),RoundedCornerShape(7.dp))
            .padding(horizontal=7.dp,vertical=3.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(if(style.isMax) "Lv.$level · MAX" else "Lv.$level",color=Color(style.primary),fontWeight=FontWeight.SemiBold,
                style=MaterialTheme.typography.labelSmall.copy(textDirection=TextDirection.Ltr))
        }
    }
}
