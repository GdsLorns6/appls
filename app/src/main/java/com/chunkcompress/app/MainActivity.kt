package com.chunkcompress.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.chunkcompress.app.core.EngineState
import com.chunkcompress.app.ui.components.FolderBrowserDialog
import com.chunkcompress.app.ui.screens.AboutScreen
import com.chunkcompress.app.ui.screens.CompressScreen
import com.chunkcompress.app.ui.screens.DecompressScreen
import com.chunkcompress.app.ui.screens.GuideScreen
import com.chunkcompress.app.ui.screens.HistoryScreen
import com.chunkcompress.app.ui.screens.HomeScreen
import com.chunkcompress.app.ui.screens.ProgressScreen
import com.chunkcompress.app.ui.screens.SettingsScreen
import com.chunkcompress.app.ui.theme.ChunkCompressTheme
import com.chunkcompress.app.ui.theme.DarkBackground
import com.chunkcompress.app.ui.theme.DarkSurface
import com.chunkcompress.app.ui.theme.PrimaryBlue
import com.chunkcompress.app.ui.theme.TextPrimary
import com.chunkcompress.app.ui.theme.TextSecondary
import com.chunkcompress.app.ui.viewmodel.MainViewModel
import java.io.File

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private var pendingFolderAction: ((File) -> Unit)? = null

    private val openDocumentTreeLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            val resolvedFile = resolveUriToFile(uri)
            if (resolvedFile != null) {
                pendingFolderAction?.invoke(resolvedFile)
            } else {
                Toast.makeText(this, "Selected folder: ${uri.path}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* result handled */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        checkAndRequestPermissions()

        setContent {
            ChunkCompressTheme {
                MainAppScaffold()
            }
        }
    }

    private fun checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivity(intent)
                }
            }
        } else {
            val permissions = arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
            val needed = permissions.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (needed.isNotEmpty()) {
                requestPermissions(needed.toTypedArray(), 100)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun resolveUriToFile(uri: Uri): File? {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(uri)
            val split = docId.split(":")
            val type = split[0]
            val relativePath = if (split.size > 1) split[1] else ""

            if ("primary".equals(type, ignoreCase = true)) {
                File(Environment.getExternalStorageDirectory(), relativePath)
            } else {
                File("/storage/$type/$relativePath")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun MainAppScaffold() {
        val currentNavIndex by viewModel.currentNavIndex.collectAsState()
        val snapshot by viewModel.progressFlow.collectAsState()

        val isTaskActive = snapshot.state !is EngineState.Idle &&
                snapshot.state !is EngineState.Completed &&
                snapshot.state !is EngineState.Error &&
                snapshot.state !is EngineState.Cancelled

        var showProgressScreen by remember { mutableStateOf(false) }
        var showGuideScreen by remember { mutableStateOf(false) }
        var showAboutScreen by remember { mutableStateOf(false) }

        // In-App Folder Browser State
        var folderBrowserTarget by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(isTaskActive) {
            if (isTaskActive) {
                showProgressScreen = true
            }
        }

        Scaffold(
            bottomBar = {
                if (!showProgressScreen && !showGuideScreen && !showAboutScreen) {
                    NavigationBar(
                        containerColor = DarkSurface,
                        contentColor = TextPrimary
                    ) {
                        NavigationBarItem(
                            icon = { Icon(Icons.Default.Dashboard, contentDescription = "Home") },
                            label = { Text("Home") },
                            selected = currentNavIndex == 0,
                            onClick = { viewModel.setNavIndex(0) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = TextPrimary,
                                indicatorColor = PrimaryBlue,
                                unselectedIconColor = TextSecondary,
                                unselectedTextColor = TextSecondary
                            )
                        )
                        NavigationBarItem(
                            icon = { Icon(Icons.Default.Archive, contentDescription = "Compress") },
                            label = { Text("Compress") },
                            selected = currentNavIndex == 1,
                            onClick = { viewModel.setNavIndex(1) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = TextPrimary,
                                indicatorColor = PrimaryBlue,
                                unselectedIconColor = TextSecondary,
                                unselectedTextColor = TextSecondary
                            )
                        )
                        NavigationBarItem(
                            icon = { Icon(Icons.Default.Unarchive, contentDescription = "Restore") },
                            label = { Text("Restore") },
                            selected = currentNavIndex == 2,
                            onClick = { viewModel.setNavIndex(2) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = TextPrimary,
                                indicatorColor = PrimaryBlue,
                                unselectedIconColor = TextSecondary,
                                unselectedTextColor = TextSecondary
                            )
                        )
                        NavigationBarItem(
                            icon = { Icon(Icons.Default.History, contentDescription = "History") },
                            label = { Text("History") },
                            selected = currentNavIndex == 3,
                            onClick = {
                                viewModel.loadHistory()
                                viewModel.setNavIndex(3)
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = TextPrimary,
                                indicatorColor = PrimaryBlue,
                                unselectedIconColor = TextSecondary,
                                unselectedTextColor = TextSecondary
                            )
                        )
                        NavigationBarItem(
                            icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                            label = { Text("Settings") },
                            selected = currentNavIndex == 4,
                            onClick = { viewModel.setNavIndex(4) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = TextPrimary,
                                indicatorColor = PrimaryBlue,
                                unselectedIconColor = TextSecondary,
                                unselectedTextColor = TextSecondary
                            )
                        )
                    }
                }
            }
        ) { innerPadding ->
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                color = DarkBackground
            ) {
                if (showProgressScreen) {
                    ProgressScreen(
                        viewModel = viewModel,
                        onBack = { showProgressScreen = false }
                    )
                } else if (showGuideScreen) {
                    GuideScreen()
                } else if (showAboutScreen) {
                    AboutScreen(onBack = { showAboutScreen = false })
                } else {
                    when (currentNavIndex) {
                        0 -> HomeScreen(
                            viewModel = viewModel,
                            onNavigateCompress = { viewModel.setNavIndex(1) },
                            onNavigateDecompress = { viewModel.setNavIndex(2) },
                            onNavigateHistory = { viewModel.setNavIndex(3) },
                            onOpenGuide = { showGuideScreen = true }
                        )
                        1 -> CompressScreen(
                            viewModel = viewModel,
                            onPickSourceFolder = { folderBrowserTarget = "source" },
                            onPickDestFolder = { folderBrowserTarget = "dest" },
                            onStartCompression = {
                                showProgressScreen = true
                                viewModel.startCompression()
                            }
                        )
                        2 -> DecompressScreen(
                            viewModel = viewModel,
                            onPickChunksFolder = { folderBrowserTarget = "decompress_src" },
                            onPickDestFolder = { folderBrowserTarget = "decompress_dst" },
                            onStartDecompression = {
                                showProgressScreen = true
                                viewModel.startDecompression()
                            }
                        )
                        3 -> HistoryScreen(
                            viewModel = viewModel,
                            onResumeSession = {
                                viewModel.setNavIndex(1)
                            }
                        )
                        4 -> SettingsScreen(
                            viewModel = viewModel,
                            onNavigateAbout = { showAboutScreen = true }
                        )
                    }
                }
            }
        }

        // Fast In-App Folder Browser Dialog
        if (folderBrowserTarget != null) {
            val title = when (folderBrowserTarget) {
                "source" -> "Select 50 GB Source Folder"
                "dest" -> "Select Chunk Output Folder"
                "decompress_src" -> "Select Folder Containing Chunks"
                "decompress_dst" -> "Select Restoration Destination"
                else -> "Select Folder"
            }
            FolderBrowserDialog(
                title = title,
                onFolderSelected = { selected ->
                    when (folderBrowserTarget) {
                        "source" -> viewModel.setSourceDirectory(selected)
                        "dest" -> viewModel.setDestinationDirectory(selected)
                        "decompress_src" -> viewModel.setDecompressSourceDir(selected)
                        "decompress_dst" -> viewModel.setDecompressDestDir(selected)
                    }
                    folderBrowserTarget = null
                },
                onDismiss = { folderBrowserTarget = null }
            )
        }
    }
}
