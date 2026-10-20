package com.clipsync.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.clipsync.app.data.PairedPc
import com.clipsync.app.data.Repository
import com.clipsync.app.service.ReceiverService
import kotlinx.coroutines.flow.map

/**
 * "The database hasn't answered yet" must not look like "no PC is paired".
 * The old code used `null` for both, so on every cold start the NavHost was
 * built with startDestination = "pairing" a few milliseconds before Room
 * delivered the real value - and NavHost ignores later changes to its start
 * destination, so a paired phone landed on the QR scanner every launch.
 */
private sealed interface PairedState {
    data object Loading : PairedState
    data class Known(val pc: PairedPc?) : PairedState
}

class MainActivity : ComponentActivity() {
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* fine either way */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val repository = Repository(applicationContext)

        setContent {
            ClipSyncTheme {
                val pairedFlow = remember { repository.pairedPc.map<PairedPc?, PairedState> { PairedState.Known(it) } }
                val state by pairedFlow.collectAsState(initial = PairedState.Loading)

                when (val current = state) {
                    PairedState.Loading -> Unit // a blank frame or two while Room answers
                    is PairedState.Known -> {
                        val pc = current.pc

                        // Keep the receiver alive whenever a PC is paired, not just
                        // while this screen happens to be open - and stop it once
                        // the PC is unpaired, so no listener outlives its purpose.
                        LaunchedEffect(pc?.deviceId) {
                            val serviceIntent = Intent(this@MainActivity, ReceiverService::class.java)
                            if (pc != null) {
                                try {
                                    ContextCompat.startForegroundService(this@MainActivity, serviceIntent)
                                } catch (e: Exception) {
                                    Log.w("ClipSync", "could not start ReceiverService", e)
                                }
                            } else {
                                stopService(serviceIntent)
                            }
                        }

                        val navController = rememberNavController()
                        NavHost(navController, startDestination = if (pc == null) "pairing" else "home") {
                            composable("home") {
                                HomeScreen(
                                    repository = repository,
                                    onSettings = { navController.navigate("settings") },
                                )
                            }
                            composable("pairing") {
                                PairingScreen(
                                    repository = repository,
                                    onPaired = {
                                        navController.navigate("home") { popUpTo("pairing") { inclusive = true } }
                                    },
                                )
                            }
                            composable("settings") {
                                SettingsScreen(
                                    repository = repository,
                                    onBack = { navController.popBackStack() },
                                    onUnpaired = {
                                        navController.navigate("pairing") { popUpTo("home") { inclusive = true } }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
