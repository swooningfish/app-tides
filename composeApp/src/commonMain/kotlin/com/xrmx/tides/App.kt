package com.xrmx.tides

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.SnackbarHost
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.platform.LocalFontFamilyResolver
import com.xrmx.tides.data.Storage
import com.xrmx.tides.platform.PlatformBackHandler
import com.xrmx.tides.platform.PlatformServices
import com.xrmx.tides.ui.Screen
import com.xrmx.tides.ui.TidesViewModel
import com.xrmx.tides.ui.screens.DisclaimerScreen
import com.xrmx.tides.ui.screens.HomeScreen
import com.xrmx.tides.ui.screens.SettingsScreen
import com.xrmx.tides.ui.screens.StationsScreen
import com.xrmx.tides.ui.theme.TidesTheme
import kotlinx.coroutines.delay
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone

@Composable
fun App(services: PlatformServices) {
    val scope = rememberCoroutineScope()
    val timeZone = remember { TimeZone.currentSystemDefault() }
    val viewModel = remember { TidesViewModel(services, Storage(), scope, timeZone) }
    val fontResolver = LocalFontFamilyResolver.current
    SideEffect { viewModel.fontResolver = fontResolver }

    val state by viewModel.state.collectAsState()
    val now by produceState(Clock.System.now()) {
        while (true) {
            delay(30_000)
            value = Clock.System.now()
        }
    }
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    PlatformBackHandler(enabled = state.showingDisclaimer || state.screen != Screen.HOME) {
        if (state.showingDisclaimer) viewModel.closeDisclaimer() else viewModel.navigate(Screen.HOME)
    }

    TidesTheme {
        Box(Modifier.fillMaxSize()) {
            when {
                !state.disclaimerAccepted -> DisclaimerScreen(firstRun = true, onAccept = viewModel::acceptDisclaimer)
                state.showingDisclaimer -> DisclaimerScreen(firstRun = false, onAccept = viewModel::closeDisclaimer)
                else -> when (state.screen) {
                    Screen.HOME -> HomeScreen(state, now, timeZone, viewModel)
                    Screen.STATIONS -> StationsScreen(state, viewModel)
                    Screen.SETTINGS -> SettingsScreen(state, viewModel)
                }
            }
            SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
        }
    }
}
