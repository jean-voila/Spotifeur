package fr.spotifeur.presentation.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.unit.sp
import fr.spotifeur.ui.theme.GoogleSansRounded
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.spotifeur.data.Music
import fr.spotifeur.data.MusicKind
import fr.spotifeur.data.preferences.ShelfStyle
import fr.spotifeur.presentation.components.SmartImage
import fr.spotifeur.presentation.viewmodel.PlayerViewModel

@Composable
private fun Cover(item: Music, ui: PlayerViewModel, placeholder: Color, shape: androidx.compose.ui.graphics.Shape, modifier: Modifier) {
    Box(modifier.clip(shape).background(placeholder), contentAlignment = Alignment.Center) {
        if (item.id == ui.likedSongs.id) Icon(Icons.Rounded.Favorite, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
        else if (item.imageUrl == null) Icon(Icons.Rounded.MusicNote, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        else SmartImage(model = item.imageUrl, contentDescription = item.title, contentScale = ContentScale.Crop, shape = shape, modifier = Modifier.fillMaxSize())
    }
}

/** Saturated then darkened: the blurred cover keeps its colours but never reads as a pale wash. */
private val BlurredCoverFilter = ColorFilter.colorMatrix(
    ColorMatrix().apply {
        setToSaturation(1.5f)
        timesAssign(ColorMatrix().apply { setToScale(0.45f, 0.45f, 0.45f, 1f) })
    }
)

/** Darker towards the bottom, where the texts are. */
private val BlurredCoverVeil = Brush.verticalGradient(
    0f to Color.Black.copy(alpha = 0.1f),
    1f to Color.Black.copy(alpha = 0.5f),
)

/** The cover itself, blurred, saturated and darkened: the card's background continues the artwork. */
@Composable
internal fun BoxScope.BlurredCover(imageUrl: String?) {
    val blurAvailable = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
    if (imageUrl != null) SmartImage(
        model = imageUrl, contentDescription = null, contentScale = ContentScale.Crop,
        targetSize = coil.size.Size(160, 160),
        colorFilter = BlurredCoverFilter,
        // Enlarged so the blur's faded rim falls outside the card and only the full colour shows.
        modifier = Modifier.matchParentSize().graphicsLayer { scaleX = 1.6f; scaleY = 1.6f }.blur(40.dp, BlurredEdgeTreatment.Unbounded),
    ) else Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.surfaceContainerHighest))
    Box(Modifier.matchParentSize().background(BlurredCoverVeil))
    // Without blur (Android 11 and older) the plain picture needs a stronger veil under the text.
    if (!blurAvailable) Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.3f)))
}

private val CardTitleShadow = Shadow(Color.Black.copy(alpha = 0.35f), blurRadius = 8f)

@Composable
private fun CardTexts(item: Music, centered: Boolean, modifier: Modifier) {
    Column(modifier) {
        Text(
            item.title, Modifier.fillMaxWidth(),
            fontFamily = GoogleSansRounded, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, lineHeight = 19.sp,
            color = Color.White, style = MaterialTheme.typography.titleMedium.copy(shadow = CardTitleShadow),
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = if (centered) TextAlign.Center else TextAlign.Start,
        )
        Text(
            item.subtitle.ifBlank { item.kind.label }, Modifier.fillMaxWidth().padding(top = 1.dp),
            fontFamily = GoogleSansRounded, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp,
            color = Color.White.copy(alpha = 0.82f), style = MaterialTheme.typography.bodySmall.copy(shadow = CardTitleShadow),
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = if (centered) TextAlign.Center else TextAlign.Start,
        )
    }
}

/** A cover with its title underneath, on the blurred continuation of the same cover. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ShelfCoverCard(item: Music, ui: PlayerViewModel, modifier: Modifier, onClick: () -> Unit, onLongClick: () -> Unit) {
    val round = item.kind == MusicKind.Artist
    val shape = RoundedCornerShape(30.dp)
    Box(modifier.clip(shape).combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        BlurredCover(item.imageUrl)
        Column(Modifier.padding(7.dp)) {
            Cover(item, ui, MaterialTheme.colorScheme.surfaceContainerHighest, if (round) CircleShape else RoundedCornerShape(23.dp),
                Modifier.fillMaxWidth().aspectRatio(1f))
            CardTexts(item, round, Modifier.padding(start = 7.dp, end = 7.dp, top = 9.dp, bottom = 7.dp))
        }
    }
}

/** One item per line: small cover, title and subtitle, on the blurred cover. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ShelfListRow(item: Music, ui: PlayerViewModel, onClick: () -> Unit, onLongClick: () -> Unit) {
    val shape = RoundedCornerShape(26.dp)
    Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp).clip(shape).combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        BlurredCover(item.imageUrl)
        Row(Modifier.padding(7.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(item, ui, MaterialTheme.colorScheme.surfaceContainerHighest,
                if (item.kind == MusicKind.Artist) CircleShape else RoundedCornerShape(19.dp), Modifier.size(58.dp))
            CardTexts(item, false, Modifier.weight(1f).padding(horizontal = 14.dp))
        }
    }
}

/** Lays out a shelf's items in the chosen [style]. Pills are drawn by the caller. */
@Composable
internal fun ShelfItems(
    items: List<Music>,
    style: ShelfStyle,
    ui: PlayerViewModel,
    onClick: (Music) -> Unit,
    onLongClick: (Music) -> Unit,
    pill: @Composable (Music, Modifier) -> Unit,
) {
    when (style) {
        ShelfStyle.CAROUSEL, ShelfStyle.LARGE -> {
            val width: Dp = if (style == ShelfStyle.LARGE) 212.dp else 152.dp
            LazyRow(contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(items, key = Music::id, contentType = { it.kind }) { item ->
                    ShelfCoverCard(item, ui, Modifier.width(width), { onClick(item) }, { onLongClick(item) })
                }
            }
        }
        ShelfStyle.GRID -> Column(Modifier.padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { item -> ShelfCoverCard(item, ui, Modifier.weight(1f), { onClick(item) }, { onLongClick(item) }) }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        ShelfStyle.LIST -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items.forEach { item -> ShelfListRow(item, ui, { onClick(item) }, { onLongClick(item) }) }
        }
        else -> Column(Modifier.padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { item -> pill(item, Modifier.weight(1f)) }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}
