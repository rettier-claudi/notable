package com.ethran.notable.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ethran.notable.R
import com.ethran.notable.ui.LocalSnackContext
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.utils.FrontLight
import com.ethran.notable.utils.FrontLightState
import compose.icons.FeatherIcons
import compose.icons.feathericons.Moon
import compose.icons.feathericons.Sun
import kotlinx.coroutines.launch

/**
 * Fork: the front-light state for a light button, kept current while the button is on screen.
 * Read again whenever the window gets focus back — pulling down the Boox control centre takes it,
 * so a change made there shows when the centre closes; returning to the app does the same.
 */
@Composable
fun rememberFrontLightState(): FrontLightState {
    val context = LocalContext.current
    val state by FrontLight.state.collectAsStateWithLifecycle()
    val focused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(focused) {
        if (focused) FrontLight.refresh(context)
    }
    return state
}

/** Switches the light; a snack says so when the firmware left it as it was. */
@Composable
fun rememberFrontLightToggle(): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackState = LocalSnackContext.current
    val stillOn = stringResource(R.string.front_light_still_on)
    val stillOff = stringResource(R.string.front_light_still_off)
    return {
        scope.launch {
            val message = when (FrontLight.toggle(context)) {
                FrontLightState.ON to FrontLightState.ON -> stillOn
                FrontLightState.OFF to FrontLightState.OFF -> stillOff
                else -> null
            }
            // Fixed id: repeated taps refresh the one snack instead of stacking copies.
            if (message != null) snackState.showOrUpdateSnack(
                SnackConf(id = "front-light", text = message, duration = 4000)
            )
        }
    }
}

/** Sun while the light is on, moon while it is off. */
fun frontLightIcon(state: FrontLightState): ImageVector =
    if (state == FrontLightState.OFF) FeatherIcons.Moon else FeatherIcons.Sun
