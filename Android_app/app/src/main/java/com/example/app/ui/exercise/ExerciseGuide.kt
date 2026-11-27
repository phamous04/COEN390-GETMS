package com.example.app.ui.exercise

import android.provider.Settings
import androidx.annotation.DrawableRes
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.app.R
import com.example.app.ui.components.PrimaryButton
import com.example.app.ui.components.ReBandCard
import com.example.app.ui.pairing.MessageBanner
import com.example.app.ui.pairing.OutlineAction
import com.example.app.ui.theme.StatusGrey
import com.example.app.ui.theme.Teal
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ---- Intro -------------------------------------------------------------------------------------

private val measured = listOf(
    "Muscle effort" to "A sensor on your biceps picks up how hard the muscle is working.",
    "Speed" to "How fast you curl your arm up and lower it again.",
    "Stability" to "How steady and in line your arm stays during each curl.",
)

/** What the wearable measures during the exercise, in plain words. */
@Composable
internal fun MeasuredCards() {
    Text("What we measure", style = MaterialTheme.typography.titleMedium)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        measured.forEach { (title, text) ->
            ReBandCard {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, color = Teal)
                    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// ---- Artwork -----------------------------------------------------------------------------------

/**
 * Guide artwork (res/drawable, sources in Android_app/art). Every picture is drawn for the right
 * arm in a single ink, so it is mirrored for the left arm and tinted with the theme's text color.
 */
@Composable
internal fun ArmArt(@DrawableRes art: Int, arm: Arm, contentDescription: String?, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(art),
        contentDescription = contentDescription,
        modifier = modifier.graphicsLayer(scaleX = if (arm == Arm.LEFT) -1f else 1f),
        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface),
    )
}

// ---- Placement guide ---------------------------------------------------------------------------

/** One page of the placement guide. [describe] says what the picture shows for "left arm" / "right arm". */
internal class PlacementPage(
    @DrawableRes val art: Int,
    val title: String,
    val caption: String,
    val describe: (arm: String) -> String,
)

internal val PlacementPages = listOf(
    PlacementPage(
        art = R.drawable.placement_emg,
        title = "Muscle sensor on the biceps",
        caption = "Stick the muscle sensor on the middle of your biceps, with its two electrodes in a line along your arm. " +
            "Place the electrode on the short wire beside the muscle, not on it.",
        describe = { arm ->
            "Front of the $arm: the muscle sensor sits on the middle of the biceps with its two electrodes in line " +
                "with the arm, and the electrode on the short wire sits beside the muscle."
        },
    ),
    PlacementPage(
        art = R.drawable.placement_imu,
        title = "Motion sensors above and below the elbow",
        caption = "Strap the upper-arm motion sensor just above your elbow and the forearm motion sensor just below it, " +
            "on the outer side of your arm. Both must face the same way round.",
        describe = { arm ->
            "Outer side of the $arm: one motion sensor is strapped just above the elbow and one just below it, " +
                "both the same way round."
        },
    ),
    PlacementPage(
        art = R.drawable.placement_strap,
        title = "Snug straps, loose cables",
        caption = "Tighten the straps until the sensors can't slide, but not so tight that they pinch. " +
            "Leave some slack in the cables so they don't pull on the sensors when you bend your elbow.",
        describe = { arm ->
            "Outer side of the $arm with the elbow bent: the straps lie flat and the cable between the two " +
                "sensors hangs slightly loose."
        },
    ),
)

/**
 * How to wear the ReBand on [arm], as swipeable pages with "Back" / "Next" and "Done, calibrate"
 * on the last page. [pager] belongs to the screen, so that Back can page through the guide too.
 */
@Composable
internal fun PlacementGuide(arm: Arm, pager: PagerState, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val armName = arm.label.lowercase()
    val current = pager.currentPage
    ReBandCard {
        Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) { index ->
                val page = PlacementPages[index]
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ArmArt(page.art, arm, page.describe(armName), Modifier.size(220.dp))
                    Text(page.title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    Text(
                        page.caption,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        minLines = 4,
                    )
                }
            }
            PageDots(current, PlacementPages.size)
        }
    }
    if (current == PlacementPages.lastIndex) {
        PrimaryButton("Done, calibrate", onClick = onDone)
    } else {
        PrimaryButton("Next", onClick = { scope.launch { pager.animateScrollToPage(current + 1) } })
    }
    if (current > 0) {
        OutlineAction("Back", Teal, { scope.launch { pager.animateScrollToPage(current - 1) } }, Modifier.fillMaxWidth())
    }
}

@Composable
private fun PageDots(current: Int, count: Int) {
    Row(
        Modifier.fillMaxWidth().semantics { contentDescription = "Page ${current + 1} of $count" },
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { i ->
            Box(Modifier.size(if (i == current) 10.dp else 8.dp).clip(CircleShape).background(if (i == current) Teal else StatusGrey))
        }
    }
}

/** Before the healthy-arm calibration: how to put the ReBand on that arm. */
@Composable
internal fun PlacementStep(state: ExerciseUiState, vm: ExerciseViewModel, pager: PagerState) {
    val arm = placementArm(state.step, state.exercisingArm) ?: return
    Text(
        "Put the ReBand on your healthy arm (${arm.label.lowercase()}) first. It is measured once, as your reference.",
        style = MaterialTheme.typography.bodyLarge,
    )
    PlacementGuide(arm, pager, onDone = vm::confirmPlacement)
}

// ---- Curl tutorial -----------------------------------------------------------------------------

private const val CURL_CAPTION = "Curl all the way up, then lower slowly. Keep your elbow by your side."
private const val CURL_FRAME_MS = 1_000L

/** False when the system animator duration scale is 0 ("Remove animations" / animations off). */
@Composable
private fun rememberAnimationsEnabled(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    }
}

/**
 * The curl as two frames (arm down, arm up), each [frame] wide, swapped about once a second.
 * With system animations off, both frames are shown side by side instead.
 */
@Composable
internal fun CurlAnimation(arm: Arm, frame: Dp, modifier: Modifier = Modifier) {
    val description = "Bicep curl with the ${arm.label.lowercase()}: the arm starts straight with the elbow at the side, " +
        "curls all the way up, then lowers again."
    val described = modifier.semantics { contentDescription = description }
    if (!rememberAnimationsEnabled()) {
        Row(described.widthIn(max = frame * 2), horizontalArrangement = Arrangement.Center) {
            ArmArt(R.drawable.tutorial_curl_down, arm, null, Modifier.weight(1f).aspectRatio(1f))
            ArmArt(R.drawable.tutorial_curl_up, arm, null, Modifier.weight(1f).aspectRatio(1f))
        }
        return
    }
    var curled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(CURL_FRAME_MS)
            curled = !curled
        }
    }
    Crossfade(targetState = curled, modifier = described, animationSpec = tween(300), label = "curl") { up ->
        ArmArt(if (up) R.drawable.tutorial_curl_up else R.drawable.tutorial_curl_down, arm, null, Modifier.size(frame))
    }
}

/** After the healthy-arm calibration: what one curl looks like, before the reference curl is recorded. */
@Composable
internal fun TutorialStep(state: ExerciseUiState, vm: ExerciseViewModel) {
    val arm = state.referenceArm ?: return
    MessageBanner("Healthy arm calibrated. Here is the movement.", isError = false)
    ReBandCard {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CurlAnimation(arm, frame = 220.dp)
            Text(CURL_CAPTION, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        }
    }
    PrimaryButton("I'm ready", onClick = vm::confirmTutorial, height = 64.dp)
    OutlineAction("Calibrate healthy arm again", Teal, vm::restartCalibration, Modifier.fillMaxWidth())
}
