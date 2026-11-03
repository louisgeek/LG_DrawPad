package com.louisgeek.lgdrawpad

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.louisgeek.drawpad.DrawPad
import com.louisgeek.drawpad.rememberDrawPadState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DrawPadHostActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state = rememberDrawPadState()
            var feedback by remember { mutableStateOf<String?>(null) }
            // Bumped on every new message so a repeated message (identical text) still restarts the
            // auto-dismiss timer instead of being cleared early by the previous timer.
            var feedbackNonce by remember { mutableStateOf(0) }
            // Auto-dismiss transient feedback so a one-off import/export error can't linger forever.
            LaunchedEffect(feedbackNonce) {
                if (feedback != null) {
                    delay(4000)
                    feedback = null
                }
            }
            val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
                if (uri != null) lifecycleScope.launch {
                    feedback = null
                    runCatching {
                        val bytes = withContext(Dispatchers.IO) {
                            contentResolver.openInputStream(uri)?.use { it.readBytes() }
                                ?: error("无法读取图片")
                        }
                        state.importBackgroundPng(bytes)
                    }.onFailure {
                        feedback = it.message ?: "导入失败"
                        feedbackNonce++
                    }
                }
            }
            MaterialTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    Column {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
                            TextButton(
                                onClick = {
                                    feedback = null
                                    importLauncher.launch("image/*")
                                },
                                modifier = Modifier.heightIn(min = 48.dp),
                                // Document-mutating entry: block it until the restore finishes so a
                                // concurrent import can't race the restored snapshot.
                                enabled = !state.uiState.value.isLoading,
                            ) { Text("导入图片") }
                            TextButton(
                                onClick = {
                                    feedback = null
                                    lifecycleScope.launch {
                                        runCatching { sharePng(state.exportPng(1600, 2400)) }
                                            .onFailure {
                                                feedback = it.message ?: "导出失败"
                                                feedbackNonce++
                                            }
                                    }
                                },
                                modifier = Modifier.heightIn(min = 48.dp),
                                enabled = !state.uiState.value.isEmpty && !state.uiState.value.isLoading,
                            ) { Text("导出 PNG") }
                        }
                        feedback?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
                        DrawPad(state = state, stylusOnly = false, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }

    private suspend fun sharePng(bytes: ByteArray) {
        val imageFile = withContext(Dispatchers.IO) {
            val directory = java.io.File(cacheDir, "shared").apply { mkdirs() }
            directory.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 24 * 60 * 60 * 1000L }
                ?.forEach(java.io.File::delete)
            java.io.File(directory, "drawpad-${java.util.UUID.randomUUID()}.png").apply { writeBytes(bytes) }
        }
        val uri = androidx.core.content.FileProvider.getUriForFile(
            this,
            "$packageName.fileprovider",
            imageFile,
        )
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "分享画布"))
    }
}