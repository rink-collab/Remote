package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.example.model.AppRole
import com.example.ui.screens.ClientGalleryScreen
import com.example.ui.screens.HostScreen
import com.example.ui.screens.RoleSelectScreen
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.PeerMediaViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: PeerMediaViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DarkBackground
                ) {
                    MainAppContent(viewModel = viewModel)
                }
            }
        }
    }
}

@Composable
fun MainAppContent(viewModel: PeerMediaViewModel) {
    val appRole by viewModel.appRole.collectAsState()

    // Permission Launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Handle permissions granted
    }

    LaunchedEffect(Unit) {
        val permissionsToRequest = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.READ_MEDIA_IMAGES)
            permissionsToRequest.add(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }

        permissionLauncher.launch(permissionsToRequest.toTypedArray())
    }

    when (appRole) {
        AppRole.NONE -> {
            RoleSelectScreen(
                onSelectRole = { role ->
                    viewModel.selectRole(role)
                }
            )
        }
        AppRole.HOST -> {
            BackHandler {
                viewModel.resetToRoleSelection()
            }
            HostScreen(
                viewModel = viewModel,
                onBack = {
                    viewModel.resetToRoleSelection()
                }
            )
        }
        AppRole.CLIENT -> {
            BackHandler {
                viewModel.resetToRoleSelection()
            }
            ClientGalleryScreen(
                viewModel = viewModel,
                onBack = {
                    viewModel.resetToRoleSelection()
                }
            )
        }
    }
}
