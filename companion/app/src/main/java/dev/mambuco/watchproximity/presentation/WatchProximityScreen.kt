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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.*
import dev.mambuco.watchproximity.R

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
            contentPadding = PaddingValues(top = 28.dp, start = 14.dp, end = 14.dp, bottom = 32.dp)
        ) {
            // Header
            item {
                ListHeader {
                    Text(
                        text = "LAPTOP PROXIMITY",
                        style = MaterialTheme.typography.caption2,
                        color = MaterialTheme.colors.onSurfaceVariant
                    )
                }
            }

            // Laptop Status Card
            item {
                Card(
                    onClick = { viewModel.refreshStatus() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = state.laptopName,
                                style = MaterialTheme.typography.title3,
                                color = MaterialTheme.colors.onSurface
                            )

                            if (state.connected) {
                                val zoneColor = if (state.zone == "DESK") {
                                    MaterialTheme.colors.secondary
                                } else {
                                    MaterialTheme.colors.primary
                                }
                                Text(
                                    text = state.zone,
                                    style = MaterialTheme.typography.caption2,
                                    fontWeight = FontWeight.Bold,
                                    color = zoneColor
                                )
                            } else {
                                Text(
                                    text = "Offline",
                                    style = MaterialTheme.typography.caption2,
                                    color = MaterialTheme.colors.onSurfaceVariant
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(2.dp))

                        val batInfo = if (state.laptopBattery != null) {
                            val powerSource = if (state.acOnline) "Charging" else "Battery"
                            "${state.laptopBattery}% • $powerSource"
                        } else {
                            if (state.acOnline) "AC Connected" else "On Battery"
                        }

                        val detailLine = if (state.rssi != null) {
                            "$batInfo • ${state.rssi} dB"
                        } else {
                            "$batInfo • ${state.distance}"
                        }

                        Text(
                            text = detailLine,
                            style = MaterialTheme.typography.caption2,
                            color = MaterialTheme.colors.onSurfaceVariant
                        )
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(2.dp))
            }

            // Primary Action: Lock Laptop
            item {
                Chip(
                    onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                        viewModel.lockLaptop()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = {
                        Text(
                            text = "Lock laptop",
                            style = MaterialTheme.typography.button
                        )
                    },
                    secondaryLabel = {
                        Text(
                            text = if (state.isLocked) "Screen is locked" else "Lock immediately",
                            style = MaterialTheme.typography.caption2
                        )
                    },
                    icon = {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_lock),
                            contentDescription = "Lock",
                            modifier = Modifier.size(ChipDefaults.IconSize)
                        )
                    }
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
                    label = {
                        Text(
                            text = "Proximity guard",
                            style = MaterialTheme.typography.button
                        )
                    },
                    secondaryLabel = {
                        Text(
                            text = if (state.guardEnabled) "Active" else "Paused",
                            style = MaterialTheme.typography.caption2
                        )
                    },
                    appIcon = {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_security),
                            contentDescription = "Security Guard",
                            modifier = Modifier.size(ChipDefaults.IconSize)
                        )
                    },
                    toggleControl = {
                        Switch(
                            checked = state.guardEnabled
                        )
                    }
                )
            }

            // Action: Ring Laptop (Locate)
            item {
                Chip(
                    onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                        viewModel.ringLaptop()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = {
                        Text(
                            text = "Ring laptop",
                            style = MaterialTheme.typography.button
                        )
                    },
                    secondaryLabel = {
                        Text(
                            text = "Play sound to find",
                            style = MaterialTheme.typography.caption2
                        )
                    },
                    icon = {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_ring),
                            contentDescription = "Ring",
                            modifier = Modifier.size(ChipDefaults.IconSize)
                        )
                    }
                )
            }

            // Action: Snooze Guard
            item {
                Chip(
                    onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                        viewModel.snoozeGuard(900)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = {
                        Text(
                            text = "Snooze",
                            style = MaterialTheme.typography.button
                        )
                    },
                    secondaryLabel = {
                        Text(
                            text = if (state.snoozeRemaining > 0) "${state.snoozeRemaining / 60}m remaining" else "Pause for 15 min",
                            style = MaterialTheme.typography.caption2
                        )
                    },
                    icon = {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_snooze),
                            contentDescription = "Snooze",
                            modifier = Modifier.size(ChipDefaults.IconSize)
                        )
                    }
                )
            }
        }
    }
}
