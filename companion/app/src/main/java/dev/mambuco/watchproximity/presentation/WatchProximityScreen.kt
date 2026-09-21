package dev.mambuco.watchproximity.presentation

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.*
import dev.mambuco.watchproximity.presentation.theme.*

@Composable
fun WatchProximityScreen(viewModel: WatchProximityViewModel = remember { WatchProximityViewModel() }) {
    val view = LocalView.current
    val listState = rememberScalingLazyListState()

    DisposableEffect(Unit) {
        viewModel.startPolling()
        onDispose {
            viewModel.stopPolling()
        }
    }

    val state = viewModel.state

    Scaffold(
        timeText = { TimeText() },
        vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) }
    ) {
        ScalingLazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            state = listState,
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(top = 28.dp, start = 12.dp, end = 12.dp, bottom = 32.dp)
        ) {
            // Header
            item {
                ListHeader {
                    Text(
                        text = "FREETOP GUARD",
                        color = CatppuccinSapphire,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                }
            }

            // Laptop Status Card
            item {
                Card(
                    onClick = { viewModel.refreshStatus() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "💻 ${state.laptopName}",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = CatppuccinText
                            )

                            val zoneColor = when (state.zone) {
                                "DESK" -> CatppuccinGreen
                                "NORMAL" -> CatppuccinBlue
                                "WARNING" -> CatppuccinYellow
                                "AWAY" -> CatppuccinRed
                                else -> CatppuccinSubtext
                            }

                            Text(
                                text = state.zone,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = zoneColor
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        val batText = if (state.laptopBattery != null) {
                            val icon = if (state.acOnline) "⚡" else "🔋"
                            "$icon ${state.laptopBattery}%"
                        } else {
                            if (state.acOnline) "⚡ AC Online" else "🔋 Battery"
                        }

                        val rssiText = if (state.rssi != null) "${state.rssi} dB" else state.distance

                        Text(
                            text = "$batText • $rssiText",
                            fontSize = 11.sp,
                            color = CatppuccinSubtext
                        )
                    }
                }
            }

            // Quick Action: Lock Laptop Now
            item {
                Spacer(modifier = Modifier.height(2.dp))
            }

            item {
                Chip(
                    onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                        viewModel.lockLaptop()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Lock Screen Now", fontWeight = FontWeight.Bold, fontSize = 12.sp) },
                    secondaryLabel = {
                        Text(
                            if (state.isLocked) "Laptop is locked" else "Immediately lock Freetop",
                            fontSize = 10.sp
                        )
                    },
                    icon = { Text("🔒", fontSize = 16.sp) },
                    colors = ChipDefaults.chipColors(
                        backgroundColor = Color(0xFF3B1E2B),
                        contentColor = CatppuccinRed
                    )
                )
            }

            // Toggle: Proximity Guard (Synced with GNOME Quick Settings)
            item {
                ToggleChip(
                    checked = state.guardEnabled,
                    onCheckedChange = {
                        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                        viewModel.toggleGuard()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Proximity Guard", fontWeight = FontWeight.SemiBold, fontSize = 12.sp) },
                    secondaryLabel = {
                        Text(
                            if (state.guardEnabled) "Active in GNOME" else "Paused in GNOME",
                            fontSize = 10.sp
                        )
                    },
                    appIcon = { Text("🛡️", fontSize = 15.sp) },
                    toggleControl = {
                        Switch(
                            checked = state.guardEnabled,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = CatppuccinGreen,
                                checkedTrackColor = Color(0xFF233B28)
                            )
                        )
                    }
                )
            }

            // Action: Ring My Laptop (Audible Chime)
            item {
                Chip(
                    onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                        viewModel.ringLaptop()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Ring Laptop", fontWeight = FontWeight.SemiBold, fontSize = 12.sp) },
                    secondaryLabel = { Text("Play chime to find laptop", fontSize = 10.sp) },
                    icon = { Text("🔔", fontSize = 15.sp) },
                    colors = ChipDefaults.chipColors(
                        backgroundColor = CatppuccinMantle,
                        contentColor = CatppuccinYellow
                    )
                )
            }

            // Action: Snooze Guard (15m)
            item {
                Chip(
                    onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                        viewModel.snoozeGuard(900)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Snooze Guard (15m)", fontWeight = FontWeight.SemiBold, fontSize = 12.sp) },
                    secondaryLabel = {
                        Text(
                            if (state.snoozeRemaining > 0) "${state.snoozeRemaining / 60}m remaining" else "Pause away lock for 15m",
                            fontSize = 10.sp
                        )
                    },
                    icon = { Text("☕", fontSize = 15.sp) },
                    colors = ChipDefaults.chipColors(
                        backgroundColor = CatppuccinMantle,
                        contentColor = CatppuccinPeach
                    )
                )
            }
        }
    }
}
