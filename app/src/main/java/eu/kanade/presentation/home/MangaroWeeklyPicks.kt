package eu.kanade.presentation.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.theme.MangaroDesignSystem
import eu.kanade.tachiyomi.ui.home.WeeklyPicksState
import java.util.Locale

@Composable
internal fun MangaroWeeklyPicks(state: WeeklyPicksState,onOpen: (Long)->Unit,onRetry: ()->Unit) {
    val scroll=rememberLazyListState()
    Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal=20.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
            Column {
                Text("اختيارات المجتمع",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
                Text("تقييمات حقيقية ومختارات الأسبوع",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if(state.failed || (state.cards.isEmpty() && !state.loading)) TextButton(onClick=onRetry) {Text("إعادة المحاولة")}
        }
        LazyRow(state=scroll,flingBehavior=rememberSnapFlingBehavior(scroll),contentPadding=PaddingValues(horizontal=20.dp),horizontalArrangement=Arrangement.spacedBy(14.dp)) {
            itemsIndexed(state.cards,key={_,card->card.key}) {index,card ->
                Column(Modifier.width(244.dp).clip(RoundedCornerShape(20.dp)).background(MangaroDesignSystem.SurfaceDark)
                    .clickable {onOpen(card.manga.mangaId)}) {
                    Box(Modifier.fillMaxWidth().height(238.dp)) {
                        MangaCover.Book(card.manga.coverData,Modifier.fillMaxSize(),shape=RoundedCornerShape(0.dp))
                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent,Color(0xB00C0812)))))
                        Surface(Modifier.align(Alignment.TopStart).padding(12.dp),color=Color(0xE61B1425),shape=RoundedCornerShape(10.dp)) {
                            Text("#${index+1}",Modifier.padding(horizontal=12.dp,vertical=7.dp),color=MangaroDesignSystem.GoldPrimary,fontWeight=FontWeight.Bold,style=MaterialTheme.typography.labelLarge.copy(textDirection=TextDirection.Ltr))
                        }
                        if(card.rating==null) Text("اختيار الأسبوع",Modifier.align(Alignment.BottomStart).padding(14.dp),style=MaterialTheme.typography.labelMedium,color=Color(0xFFE4C77A))
                    }
                    Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
                        Text(card.manga.title,style=MaterialTheme.typography.titleMedium.copy(textDirection=TextDirection.Content),fontWeight=FontWeight.Bold,maxLines=2,minLines=2,overflow=TextOverflow.Ellipsis)
                        val rating=card.rating
                        Text(if(rating!=null) "★ ${String.format(Locale.ROOT,"%.1f",rating.average)}  ·  ${rating.count} تقييم" else "عمل يستحق الاكتشاف",
                            style=MaterialTheme.typography.labelMedium,color=if(rating!=null) MangaroDesignSystem.GoldPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if(state.cards.isEmpty() && state.loading) items(2) {
                Column(Modifier.width(244.dp).height(340.dp).clip(RoundedCornerShape(20.dp)).background(MangaroDesignSystem.SurfaceDark)) {
                    Box(Modifier.fillMaxWidth().height(238.dp).background(Color(0xFF241C34)))
                    Box(Modifier.padding(16.dp).fillMaxWidth().height(16.dp).background(Color(0xFF33283E),RoundedCornerShape(4.dp)))
                }
            }
        }
        if(state.cards.isEmpty() && !state.loading) Text("المختارات غير متاحة الآن، حاول عند توفر الاتصال",Modifier.padding(horizontal=20.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
