package eu.kanade.presentation.stories

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.novels.NovelCover
import eu.kanade.presentation.theme.MangaroDesignSystem
import eu.kanade.tachiyomi.data.stories.ConnectedStoryUiModel
import eu.kanade.tachiyomi.data.stories.StoryDestination

/** Heading, actual cover/title and arrow only. Evidence/provenance never enters visible copy. */
@Composable
internal fun ConnectedStoryCard(model: ConnectedStoryUiModel, onClick: () -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Column(Modifier.fillMaxWidth(),verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(model.heading,modifier = Modifier.semantics { heading() },
                color = MangaroDesignSystem.TextSecondary,style = MaterialTheme.typography.titleSmall)
            Surface(onClick = onClick,modifier = Modifier.fillMaxWidth(),shape = RoundedCornerShape(18.dp),
                color = MangaroDesignSystem.SurfaceDark,
                border = BorderStroke(.5.dp,MangaroDesignSystem.LavenderPrimary.copy(alpha = .22f))) {
                Row(Modifier.padding(12.dp),horizontalArrangement = Arrangement.spacedBy(14.dp),verticalAlignment = Alignment.CenterVertically) {
                    when (val target = model.selected) {
                        is StoryDestination.Comic -> MangaCover.Book(target.manga,Modifier.width(52.dp),contentDescription = "",shape = RoundedCornerShape(14.dp))
                        is StoryDestination.Prose -> NovelCover(target.novel,Modifier.width(52.dp).height(78.dp))
                    }
                    // Wrap naturally at accessibility font sizes; do not reduce a real title to one fragment.
                    Text(model.selected.title,Modifier.weight(1f),style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.ContentOrRtl),
                        fontWeight = FontWeight.Medium,color = MangaroDesignSystem.TextPrimary)
                    Icon(Icons.AutoMirrored.Outlined.ArrowForward,null,Modifier.size(18.dp),tint = MangaroDesignSystem.LavenderPrimary)
                }
            }
        }
    }
}
