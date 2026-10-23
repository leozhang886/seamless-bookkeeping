package com.bookkeeping.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.bookkeeping.app.data.settings.SettingsPreferences
import com.bookkeeping.app.data.settings.ThemeMode
import com.bookkeeping.app.ui.navigation.RootNavigation
import com.bookkeeping.app.ui.theme.BookkeepingAppTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import android.app.KeyguardManager
import android.app.Activity
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bookkeeping.app.data.local.BookkeepingDatabase
import kotlinx.coroutines.flow.map

// @AndroidEntryPoint = 让 Hilt 能给这个 Activity 及其下属 Composable 注入依赖
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var settingsPreferences: SettingsPreferences
    @Inject lateinit var database: BookkeepingDatabase
    private var locked by mutableStateOf(true)
    private val unlock = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) locked = false
    }

    override fun onStop() { locked = true; super.onStop() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent {
            val protection by remember { settingsPreferences.observeScreenProtection().map { it as Boolean? } }.collectAsState(initial = null)
            val lockEnabled by remember { settingsPreferences.observeAppLock().map { it as Boolean? } }.collectAsState(initial = null)
            val restoring by database.access.restoring.collectAsState()
            val generation by database.access.generation.collectAsState()
            var displayGeneration by remember { mutableStateOf(generation) }
            LaunchedEffect(restoring, generation) { if (!restoring) displayGeneration = generation }
            LaunchedEffect(protection, lockEnabled) {
                if (protection != false || lockEnabled != false) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
            val themeMode by settingsPreferences.observeThemeMode().collectAsState(initial = ThemeMode.SYSTEM)
            val systemDark = isSystemInDarkTheme()
            val dark = when (themeMode) {
                ThemeMode.SYSTEM -> systemDark
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            LaunchedEffect(dark) {
                val transparent = android.graphics.Color.TRANSPARENT
                enableEdgeToEdge(
                    statusBarStyle = if (dark) SystemBarStyle.dark(transparent)
                        else SystemBarStyle.light(transparent, transparent),
                    navigationBarStyle = if (dark) SystemBarStyle.dark(transparent)
                        else SystemBarStyle.light(transparent, transparent),
                )
            }
            BookkeepingAppTheme(themeMode = themeMode) {
                key(displayGeneration) { RootNavigation() }
                val needsUnlock = lockEnabled == true && locked
                if (restoring || needsUnlock || lockEnabled == null || protection == null) {
                    Dialog(onDismissRequest = {}, properties = DialogProperties(
                        dismissOnBackPress = false, dismissOnClickOutside = false, usePlatformDefaultWidth = false)) {
                        Surface(Modifier.fillMaxSize()) {
                            Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(if (restoring) "正在安全恢复账本…" else "账本已锁定", style = MaterialTheme.typography.headlineSmall)
                                if (needsUnlock && !restoring) Button(onClick = {
                                    getSystemService(KeyguardManager::class.java)
                                        .createConfirmDeviceCredentialIntent("解锁账本", "使用手机锁屏凭据验证身份")?.let(unlock::launch) ?: run {
                                            android.widget.Toast.makeText(this@MainActivity, "请先在系统设置中恢复锁屏密码，再返回解锁", android.widget.Toast.LENGTH_LONG).show()
                                            startActivity(android.content.Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS))
                                        }
                                }) { Text("解锁") }
                                else CircularProgressIndicator()
                            }
                        }
                    }
                }
            }
        }
    }

}
