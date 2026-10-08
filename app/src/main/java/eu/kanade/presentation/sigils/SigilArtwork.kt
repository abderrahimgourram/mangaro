package eu.kanade.presentation.sigils

import android.provider.Settings
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import mihon.domain.sigils.*
import kotlin.math.*

/** A single bounded convergence/flash; never an infinite full-screen particle surface. */
@Composable
fun SigilOpeningParticles(definition: SigilDefinition,progress: Float,modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val t=progress.coerceIn(0f,1f)
        val color=Color(definition.accent)
        val radius=min(size.width,size.height)*(.46f-.31f*t)
        val glow=sin(PI*t).toFloat().coerceIn(0f,1f)
        drawCircle(color.copy(alpha=glow*.22f),radius,center,style=Stroke(1.5f))
        repeat(12) {i ->
            val angle=(i/12f+t*.12f)*2*PI
            val point=center+Offset(cos(angle).toFloat()*radius,sin(angle).toFloat()*radius)
            drawCircle(color.copy(alpha=glow*.8f),2f+glow*2f,point)
            drawLine(color.copy(alpha=glow*.3f),point,center+(point-center)*.88f,1.5f)
        }
        val flash=(1f-abs(t-.65f)/.13f).coerceIn(0f,1f)
        drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha=flash*.18f),Color.Transparent),center,size.minDimension*.3f),size.minDimension*.3f,center)
    }
}

/** Thirty original geometric silhouettes; infinite effects exist only for visible, resumed, earned sigils. */
@Composable
fun SigilArtwork(definition: SigilDefinition, unlocked: Boolean, modifier: Modifier = Modifier, animated: Boolean = true) {
    val view=LocalView.current
    val context=LocalContext.current
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    var visible by remember { mutableStateOf(false) }
    val motion=remember(context) {Settings.Global.getFloat(context.contentResolver,Settings.Global.ANIMATOR_DURATION_SCALE,1f)>0f}
    val running=unlocked && animated && visible && motion && lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    val phase=if(running) {
        val transition=rememberInfiniteTransition(label="sigil-${definition.id}")
        transition.animateFloat(0f,1f,infiniteRepeatable(tween(6500+definition.motif*137,easing=LinearEasing)),label="sigil-orbit").value
    } else 0f
    Canvas(modifier.semantics {contentDescription=definition.name;stateDescription=if(unlocked) "ختم مفتوح" else "ختم مغلق"}.onGloballyPositioned { coordinates ->
        val rect=coordinates.boundsInWindow(); val window=android.graphics.Rect()
        visible=view.getWindowVisibleDisplayFrame(window).let {rect.overlaps(Rect(window.left.toFloat(),window.top.toFloat(),window.right.toFloat(),window.bottom.toFloat()))}
    }) {
        val scale=min(size.width,size.height)/100f
        val accent=if(unlocked) Color(definition.accent) else Color(0xFF615B72)
        val light=if(unlocked) Color(0xFFF5EBD0) else Color(0xFF8D8498)
        val breath=0.08f+0.055f*sin(phase*2*PI).toFloat()
        translate((size.width-100*scale)/2,(size.height-100*scale)/2) {scale(scale,scale,Offset.Zero) {
            drawCircle(Brush.radialGradient(listOf(accent.copy(alpha=if(unlocked) breath+0.15f else 0.04f),Color.Transparent),Offset(50f,50f),50f),50f,Offset(50f,50f))
            fun line(x1:Float,y1:Float,x2:Float,y2:Float,color:Color=accent,width:Float=2f) = drawLine(color,Offset(x1,y1),Offset(x2,y2),width,StrokeCap.Round)
            fun shape(vararg points: Pair<Float,Float>,color:Color=accent,fill:Boolean=false,width:Float=2f) {
                val p=Path().apply {moveTo(points.first().first,points.first().second);points.drop(1).forEach {lineTo(it.first,it.second)};close()}
                drawPath(p,color,style=if(fill) Fill else Stroke(width,join=StrokeJoin.Round))
            }
            fun star(x:Float,y:Float,r:Float=4f,color:Color=light) = shape(x to y-r,x+r*.4f to y,x to y+r,x-r*.4f to y,color=color,fill=true)
            fun arc(start:Float,sweep:Float,r:Float=34f,color:Color=accent) = drawArc(color,start,sweep,false,Offset(50-r,50-r),androidx.compose.ui.geometry.Size(r*2,r*2),style=Stroke(1.6f,cap=StrokeCap.Round))
            fun sword(x:Float,y:Float,angle:Float=0f) {rotate(angle,Offset(x,y)) {
                shape(x to y-28,x-4 to y+5,x to y+10,x+4 to y+5,color=light,fill=true)
                line(x-10,y+10,x+10,y+10);line(x,y+10,x,y+25,accent,4f);star(x,y+27,3f)
            }}
            fun book(x:Float=50f,y:Float=52f) {shape(x to y-17,x-25 to y-23,x-25 to y+14,x to y+20,x+25 to y+14,x+25 to y-23,color=light);line(x,y-17,x,y+20);(0..2).forEach {line(x-20,y-8+it*8,x-5,y-5+it*8);line(x+5,y-5+it*8,x+20,y-8+it*8)}}
            when(definition.motif) {
                0 -> {arc(0f,360f,24f);arc(phase*360,210f);shape(50f to 15f,57f to 29f,50f to 35f,43f to 29f,color=light);line(50f,39f,50f,66f,light,3f);star(50f,71f)}
                1 -> {shape(23f to 71f,23f to 38f,36f to 21f,64f to 21f,77f to 38f,77f to 71f);sword(50f,47f);star(24f,76f);star(76f,76f)}
                2 -> {shape(18f to 77f,18f to 39f,50f to 15f,82f to 39f,82f to 77f,70f to 77f,70f to 41f,50f to 28f,30f to 41f,30f to 77f);arc(phase*360,130f,18f,light);star(50f,52f,8f)}
                3 -> {shape(50f to 16f,82f to 37f,73f to 71f,50f to 86f,27f to 71f,18f to 37f);sword(42f,48f,-25f);sword(58f,48f,25f);star(50f,20f,5f)}
                4 -> {shape(12f to 79f,20f to 30f,34f to 18f,50f to 8f,66f to 18f,80f to 30f,88f to 79f,70f to 79f,70f to 37f,50f to 24f,30f to 37f,30f to 79f);arc(phase*360,290f,16f,light);star(50f,51f,10f)}
                5 -> {shape(32f to 80f,32f to 52f,42f to 52f,42f to 34f,50f to 22f,58f to 34f,58f to 52f,68f to 52f,68f to 80f);star(50f,15f);line(21f,82f,79f,82f,light)}
                6 -> {(0..4).forEach {i->line(24f+i*6,77f-i*11,73f-i*6,77f-i*11,if(i%2==0) accent else light,3f)};shape(50f to 13f,61f to 30f,39f to 30f);arc(phase*360,160f,40f)}
                7 -> {shape(50f to 18f,80f to 50f,50f to 82f,20f to 50f);drawOval(light,Offset(29f,40f),androidx.compose.ui.geometry.Size(42f,20f),style=Stroke(2f));drawCircle(accent,6f,Offset(50f,50f));star(50f,16f)}
                8 -> {listOf(Offset(25f,63f),Offset(41f,29f),Offset(68f,21f),Offset(78f,60f),Offset(52f,77f)).zipWithNext().forEach {line(it.first.x,it.first.y,it.second.x,it.second.y)};listOf(25f to 63f,41f to 29f,68f to 21f,78f to 60f,52f to 77f).forEach {star(it.first,it.second,6f)};arc(phase*360,180f,40f)}
                9 -> {book();arc(phase*360,220f,39f);star(50f,13f,7f);star(17f,38f);star(83f,38f)}
                10 -> {shape(28f to 25f,66f to 25f,66f to 76f,28f to 76f);drawCircle(light,5f,Offset(28f,25f),style=Stroke(2f));drawCircle(light,5f,Offset(28f,76f),style=Stroke(2f));(0..3).forEach {line(37f,36f+it*9,58f,36f+it*9)};star(69f,57f,6f)}
                11 -> {book();shape(50f to 22f,56f to 33f,50f to 42f,44f to 33f,color=accent,fill=true);line(24f,81f,76f,81f,light)}
                12 -> {sword(50f,45f,-32f);arc(phase*360,240f,33f);line(19f,73f,39f,73f);line(66f,28f,85f,28f)}
                13 -> {sword(50f,44f);sword(30f,53f,-32f);sword(70f,53f,32f);arc(180f,160f,39f,light)}
                14 -> {shape(50f to 9f,84f to 38f,78f to 75f,50f to 91f,22f to 75f,16f to 38f);sword(50f,49f);(0..2).forEach {i->arc(phase*360+i*120,70f,34f)};star(50f,13f)}
                15 -> {shape(20f to 73f,25f to 28f,39f to 41f,50f to 20f,61f to 41f,75f to 28f,80f to 73f);line(27f,64f,73f,64f,light,3f);star(50f,51f)}
                16 -> {(0..5).forEach {i->rotate(i*60f+phase*20,Offset(50f,45f)) {drawOval(accent.copy(alpha=.65f),Offset(44f,21f),androidx.compose.ui.geometry.Size(12f,25f))}};star(50f,45f,7f);line(50f,61f,50f,82f,light);shape(50f to 72f,65f to 63f,62f to 76f)}
                17 -> {shape(50f to 16f,76f to 28f,74f to 64f,50f to 83f,26f to 64f,24f to 28f,color=light);shape(34f to 50f,39f to 35f,50f to 43f,61f to 35f,66f to 50f,50f to 58f);line(34f,62f,66f,62f)}
                18 -> {arc(0f,360f,29f,light);line(50f,50f,50f,31f);line(50f,50f,68f,57f);shape(50f to 10f,58f to 22f,42f to 22f);shape(50f to 90f,42f to 78f,58f to 78f);arc(phase*360,120f,38f)}
                19 -> {shape(14f to 77f,14f to 41f,23f to 41f,23f to 31f,32f to 31f,32f to 44f,41f to 44f,41f to 24f,50f to 12f,59f to 24f,59f to 44f,68f to 44f,68f to 31f,77f to 31f,77f to 41f,86f to 41f,86f to 77f);star(50f,51f,7f);line(50f,60f,50f,78f,light,4f)}
                20 -> {shape(50f to 17f,76f to 39f,64f to 75f,36f to 75f,24f to 39f,color=light);line(24f,39f,76f,39f);line(50f,17f,39f,39f);line(50f,17f,61f,39f);line(39f,39f,50f,75f);line(61f,39f,50f,75f)}
                21 -> {(0..2).forEach {i->shape(22f+i*5 to 30f+i*11,65f+i*5 to 30f+i*11,65f+i*5 to 47f+i*11,22f+i*5 to 47f+i*11,color=if(i==1)light else accent)};star(78f,21f)}
                22 -> {book();line(22f,78f,78f,78f);line(22f,83f,78f,83f);star(50f,18f,6f)}
                23 -> {drawCircle(light,15f,Offset(40f,35f),style=Stroke(3f));star(40f,35f,6f);line(50f,46f,76f,73f,accent,5f);line(65f,62f,57f,70f,light,4f);line(74f,70f,66f,78f,light,4f);arc(phase*360,190f,40f)}
                24 -> {shape(19f to 23f,81f to 23f,81f to 80f,19f to 80f,color=light);(0..3).forEach {i->shape(25f+i*13 to 30f,33f+i*13 to 30f,33f+i*13 to 64f,25f+i*13 to 64f)};star(50f,73f);arc(phase*360,100f,40f)}
                25 -> {shape(21f to 25f,79f to 25f,79f to 62f,49f to 62f,32f to 78f,32f to 62f,21f to 62f);(0..2).forEach {i->star(35f+i*15,44f,3f)}}
                26 -> {shape(61f to 17f,78f to 34f,45f to 69f,27f to 77f,31f to 59f,color=light);line(30f,75f,62f,40f);line(22f,85f,78f,85f);star(24f,25f)}
                27 -> {shape(20f to 34f,80f to 34f,72f to 65f,28f to 65f);shape(50f to 13f,64f to 34f,36f to 34f,color=light);line(30f,72f,70f,72f,light,3f);line(24f,80f,76f,80f);star(50f,52f)}
                28 -> {val points=listOf(50f to 15f,80f to 42f,69f to 76f,31f to 76f,20f to 42f);points.indices.forEach {i->line(points[i].first,points[i].second,points[(i+2)%5].first,points[(i+2)%5].second)};points.forEach {star(it.first,it.second,5f)};drawCircle(light,8f,Offset(50f,48f),style=Stroke(2f))}
                29 -> {shape(50f to 8f,80f to 25f,83f to 58f,67f to 81f,50f to 92f,33f to 81f,17f to 58f,20f to 25f,color=light);shape(31f to 46f,36f to 31f,50f to 43f,64f to 31f,69f to 46f,50f to 64f);star(50f,75f,6f);arc(phase*360,100f,39f)}
            }
            if(unlocked) {
                val n=when(definition.rarity) {SigilRarity.COMMON->3;SigilRarity.RARE->4;SigilRarity.EPIC->5;SigilRarity.LEGENDARY->6}
                repeat(n) {i->
                    val angle=2*PI*(phase+i.toFloat()/n)
                    val x=50f+43*cos(angle).toFloat(); val y=50f+43*sin(angle).toFloat()
                    when(definition.world) {
                        SigilWorld.COURTS -> rotate(phase*180+i*60f,Offset(x,y)) {drawOval(accent.copy(alpha=.6f),Offset(x-1.5f,y-3f),androidx.compose.ui.geometry.Size(3f,6f))}
                        SigilWorld.MURIM -> line(x,y,x+2,y-4,accent.copy(alpha=.55f),1f)
                        SigilWorld.MANUSCRIPTS -> drawRect(accent.copy(alpha=.5f),Offset(x-1.5f,y-2f),androidx.compose.ui.geometry.Size(3f,4f))
                        else -> star(x,y,1.6f,accent.copy(alpha=.8f))
                    }
                }
            }
        }}
    }
}
