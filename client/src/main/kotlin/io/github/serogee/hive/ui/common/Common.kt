package io.github.serogee.hive.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.serogee.hive.AppContainer
import io.github.serogee.hive.core.model.AdmissionPolicy
import io.github.serogee.hive.core.model.RoomMode
import io.github.serogee.hive.core.model.RoomStatus
import io.github.serogee.hive.data.auth.SessionEndedException
import io.github.serogee.hive.data.auth.SignInException
import io.github.serogee.hive.data.remote.ApiException
import io.github.serogee.hive.ui.theme.HEXAGON_RATIO
import io.github.serogee.hive.ui.theme.HexagonShape
import io.github.serogee.hive.ui.theme.Honey
import io.github.serogee.hive.ui.theme.hiveColors
import io.github.serogee.hive.ui.theme.honeyShadow
import kotlin.coroutines.cancellation.CancellationException

val LocalAppContainer = staticCompositionLocalOf<AppContainer> { error("No AppContainer") }

/** Text for an error shown inline on a screen. */
fun Throwable.userMessage(): String = when (this) {
    is CancellationException -> throw this
    is ApiException -> error.message
    is SignInException -> message ?: "Sign-in did not work."
    is SessionEndedException -> "Sign in again."
    else -> "Can't reach the Hive server. Check your Wi-Fi and the server address in Accounts."
}

fun RoomMode.label() = when (this) {
    RoomMode.DISCUSSION -> "Discussion"
    RoomMode.ASYNCHRONOUS -> "Asynchronous"
}

fun AdmissionPolicy.label() = when (this) {
    AdmissionPolicy.EVERYONE -> "Everyone"
    AdmissionPolicy.SIGNED_IN_ONLY -> "Signed-in Players only"
    AdmissionPolicy.GUESTS_ONLY -> "Guests only"
}

fun RoomStatus.label() = when (this) {
    RoomStatus.LOBBY -> "In lobby"
    RoomStatus.LIVE -> "Live"
    RoomStatus.ENDED -> "Ended"
}

/** The bar at the top of a screen, with a caps title and an optional action. */
@Composable
fun HiveTopBar(
    title: String,
    modifier: Modifier = Modifier,
    leading: @Composable (() -> Unit)? = null,
    action: @Composable (() -> Unit)? = null,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(horizontal = 20.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leading?.invoke()
            Text(
                text = title.uppercase(),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            action?.invoke()
        }
        HorizontalDivider(thickness = 2.dp, color = MaterialTheme.hiveColors.border)
    }
}

/** One step of [Breadcrumbs]. The last one is where the user is, so it opens nothing. */
data class Crumb(val text: String, val onClick: (() -> Unit)? = null)

/** A thin row below the top bar that shows where the screen is, such as My Stacks > Biology > Edit. */
@Composable
fun Breadcrumbs(crumbs: List<Crumb>, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    // Keep the end in view, because it says what is being edited.
    LaunchedEffect(crumbs, scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
    Column(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scroll)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            crumbs.forEachIndexed { index, crumb ->
                if (index > 0) {
                    Icon(
                        Icons.Outlined.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
                val last = index == crumbs.lastIndex
                Text(
                    text = crumb.text,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (last) FontWeight.Bold else FontWeight.Normal,
                    color = if (last) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.small)
                        .then(if (crumb.onClick != null) Modifier.clickable(onClick = crumb.onClick) else Modifier)
                        .widthIn(max = 180.dp)
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    }
}

/** A page with a top bar, an optional subtitle, and scrolling content. */
@Composable
fun Page(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    crumbs: List<Crumb> = emptyList(),
    leading: @Composable (() -> Unit)? = null,
    action: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        HiveTopBar(title, leading = leading, action = action)
        if (crumbs.isNotEmpty()) Breadcrumbs(crumbs)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            content()
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** A [Page] whose content is a lazy list, for screens with many items. */
@Composable
fun LazyPage(
    title: String,
    modifier: Modifier = Modifier,
    leading: @Composable (() -> Unit)? = null,
    action: @Composable (() -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        HiveTopBar(title, leading = leading, action = action)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}

/** A small caps label above a group of fields or a value. */
@Composable
fun Caption(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Text(
        text = text.uppercase(),
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium,
        color = color,
    )
}

/** Section header with a small caps label and an optional right-aligned action (e.g. "Show All") */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    onActionClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Caption(title, modifier = Modifier.weight(1f, fill = false))
        if (actionText != null && onActionClick != null) {
            Caption(
                text = actionText,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(MaterialTheme.shapes.small)
                    .clickable(onClick = onActionClick)
                    .padding(6.dp),
            )
        }
    }
}

/** Badge with a value above a short label, such as a question count. */
@Composable
fun CountBadge(
    topText: String,
    bottomText: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(width = 52.dp, height = 48.dp)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.hiveColors.solid),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = topText,
                color = Honey,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
            )
            Text(
                text = bottomText.uppercase(),
                color = MaterialTheme.hiveColors.onSolid,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
        }
    }
}

/** A hexagon that holds a number, a letter, or an icon. */
@Composable
fun HexBadge(
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    color: Color = MaterialTheme.colorScheme.primaryContainer,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .size(width = size * HEXAGON_RATIO, height = size)
            .clip(HexagonShape)
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/** A hexagon with a number or a letter. */
@Composable
fun HexBadge(
    text: String,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    color: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
) {
    HexBadge(modifier, size, color) {
        Text(text = text, color = contentColor, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

private const val MAX_CELLS = 12

/** Progress as a row of hexagons. With more than [MAX_CELLS] steps, each cell stands for several steps. */
@Composable
fun ProgressCells(
    done: Int,
    total: Int,
    modifier: Modifier = Modifier,
) {
    if (total <= 0) return
    val cells = minOf(total, MAX_CELLS)
    val filled = done * cells / total
    Row(
        modifier = modifier.semantics {
            progressBarRangeInfo = ProgressBarRangeInfo(done.toFloat(), 0f..total.toFloat())
        },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        repeat(cells) { index ->
            Box(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .widthIn(max = 20.dp * HEXAGON_RATIO)
                    .aspectRatio(HEXAGON_RATIO)
                    .clip(HexagonShape)
                    .background(if (index < filled) Honey else MaterialTheme.hiveColors.emptyCell),
            )
        }
    }
}

/** Stat chip with a value above its label, such as a number of finished reviews. */
@Composable
fun MetricStatChip(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                text = value.uppercase(),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A graphite card with an ink border. The [main] one on a screen has the honey shadow. */
@Composable
fun SolidCard(
    modifier: Modifier = Modifier,
    main: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.medium
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(if (main) Modifier.honeyShadow(shape) else Modifier),
        shape = shape,
        color = MaterialTheme.hiveColors.solid,
        contentColor = MaterialTheme.hiveColors.onSolid,
        border = BorderStroke(2.dp, MaterialTheme.hiveColors.border),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
    }
}

/** The main card of a screen, with a honeycomb graphic. */
@Composable
fun HeroCard(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    badgeText: String? = null,
    buttonText: String? = null,
    onButtonClick: (() -> Unit)? = null,
    /** Off when another card is the main one on the screen. */
    main: Boolean = true,
    content: @Composable (ColumnScope.() -> Unit)? = null,
) {
    SolidCard(modifier, main) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                if (badgeText != null) {
                    Caption(badgeText, color = Honey)
                    Spacer(Modifier.height(4.dp))
                }
                Text(
                    text = title.uppercase(),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            HoneycombGraphic()
        }

        if (content != null) {
            content()
        }

        if (buttonText != null && onButtonClick != null) {
            HiveButton(
                text = buttonText,
                onClick = onButtonClick,
                onSolid = true,
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}

/** Five honeycomb cells that decorate a hero card. */
@Composable
private fun HoneycombGraphic() {
    val rows = listOf(listOf(true, false), listOf(true, true, true))
    val cell = 18.dp
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy((-2).dp),
    ) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                row.forEach { full ->
                    Box(
                        modifier = Modifier
                            .size(width = cell * HEXAGON_RATIO, height = cell)
                            .clip(HexagonShape)
                            .background(if (full) Honey else Honey.copy(alpha = 0.35f)),
                    )
                }
            }
        }
    }
}

/** Tag for a status or a short label. */
@Composable
fun TagPill(
    text: String,
    selected: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = if (selected) MaterialTheme.hiveColors.solid else MaterialTheme.colorScheme.primaryContainer,
        contentColor = if (selected) Honey else MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

/** Standard card. The [active] one on a screen has an ink border and the honey shadow. */
@Composable
fun SleekCard(
    modifier: Modifier = Modifier,
    active: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.medium
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(if (active) Modifier.honeyShadow(shape) else Modifier)
            .clip(shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        border = if (active) {
            BorderStroke(2.dp, MaterialTheme.hiveColors.border)
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        },
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

@Composable
fun ErrorText(message: String?) {
    if (message != null) {
        Text(
            message,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
