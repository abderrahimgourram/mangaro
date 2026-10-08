package eu.kanade.presentation.account

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.theme.MangaroDesignSystem
import tachiyomi.core.common.util.lang.WesternDigits

internal data class ShowcaseDisplayItem(val key: String,val title: String,val cover: Any?,val featured: Boolean)

/** Shared public presentation and owner preview. Data is already bounded/projected. */
@Composable
internal fun LibraryShowcase(items: List<ShowcaseDisplayItem>, onOpen: ((String)->Unit)? = null, showHeader: Boolean = true) {
    val featured = items.filter { it.featured }.take(3)
    val shelf = items.filterNot { it.featured }
    Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        if(showHeader) Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
            Text("المكتبة العامة",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold,color=Color(0xFFF0E7F7))
            Text("${WesternDigits.isolate(items.size.toString())} أعمال",style=MaterialTheme.typography.labelSmall,color=Color(0xFFB7A9C4))
        }
        if(items.isEmpty()) Text("لا توجد أعمال معروضة بعد",style=MaterialTheme.typography.bodySmall,color=Color(0xFFB7A9C4),modifier=Modifier.padding(vertical=12.dp))
        if(featured.isNotEmpty()) LazyRow(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            items(featured,key={it.key}) { item ->
                Row(Modifier.width(248.dp).clip(RoundedCornerShape(20.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF2A2031),MangaroDesignSystem.SurfaceDark)))
                    .border(.5.dp,MangaroDesignSystem.GoldPrimary.copy(alpha=.18f),RoundedCornerShape(20.dp))
                    .clickable(enabled=onOpen!=null) { onOpen?.invoke(item.key) }.padding(12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
                    MangaCover.Book(item.cover,Modifier.width(76.dp),shape=RoundedCornerShape(10.dp))
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement=Arrangement.spacedBy(4.dp),verticalAlignment=Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Star,null,Modifier.size(14.dp),tint=MangaroDesignSystem.GoldPrimary)
                            Text("عمل مميز",color=MangaroDesignSystem.GoldPrimary,style=MaterialTheme.typography.labelSmall)
                        }
                        Text(item.title,maxLines=3,overflow=TextOverflow.Ellipsis,color=Color(0xFFF0E7F7),
                            style=MaterialTheme.typography.titleSmall.copy(textDirection=TextDirection.Content),fontWeight=FontWeight.Medium)
                    }
                }
            }
        }
        shelf.chunked(3).forEach { row ->
            Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                row.forEach { item -> key(item.key) {
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable(enabled=onOpen!=null) {onOpen?.invoke(item.key)},verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        MangaCover.Book(item.cover,Modifier.fillMaxWidth(),shape=RoundedCornerShape(12.dp))
                        Text(item.title,Modifier.padding(horizontal=2.dp),maxLines=2,overflow=TextOverflow.Ellipsis,color=Color(0xFFD9CCE3),
                            style=MaterialTheme.typography.bodySmall.copy(textDirection=TextDirection.Content))
                    }
                } }
                repeat(3-row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
