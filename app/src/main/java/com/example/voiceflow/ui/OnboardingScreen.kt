package com.example.voiceflow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.SuccessGreenContainer
import com.example.ui.theme.VoicePurple
import com.example.ui.theme.WarningAmber
import com.example.ui.theme.WarningAmberContainer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    snapshot: PermissionSnapshot,
    onRequestMic: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenXiaomiAutostart: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onOpenAppDetails: () -> Unit,
    onOpenSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var testText by remember { mutableStateOf("") }
    var testPassword by remember { mutableStateOf("") }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Kurulum ve Durum",
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                actions = {
                    IconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.testTag("open_settings_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "Ayarlar",
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                HeroStatusCard(snapshot = snapshot)

                Text(
                    text = "Gerekli İzinler",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )

                PermissionRowCard(
                    icon = Icons.Filled.Mic,
                    title = "Mikrofon İzni",
                    description = "Konuşmanızı sesli dikteye çevirmek için mikrofon erişimi gerekir.",
                    granted = snapshot.micGranted,
                    actionLabel = if (snapshot.micGranted) "Verildi" else "İzin Ver",
                    testTag = "mic_permission_button",
                    onClick = onRequestMic,
                )

                PermissionRowCard(
                    icon = Icons.Filled.Layers,
                    title = "Diğer Uygulamaların Üstünde Göster",
                    description = "Dikte çubuğunun klavye üzerinde görüntülenebilmesi için gereklidir.",
                    granted = snapshot.overlayGranted,
                    actionLabel = if (snapshot.overlayGranted) "Açık" else "Ayarları Aç",
                    testTag = "overlay_permission_button",
                    onClick = onOpenOverlaySettings,
                )

                PermissionRowCard(
                    icon = Icons.Filled.AccessibilityNew,
                    title = "Erişilebilirlik Servisi",
                    description = "Metin alanlarını otomatik algılar ve onaylanan yazıyı tek dokunuşla ekler.",
                    granted = snapshot.accessibilityEnabled,
                    actionLabel = if (snapshot.accessibilityEnabled) "Etkin" else "Servisi Aç",
                    testTag = "accessibility_permission_button",
                    onClick = onOpenAccessibilitySettings,
                )

                XiaomiOptimizationCard(
                    snapshot = snapshot,
                    onOpenXiaomiAutostart = onOpenXiaomiAutostart,
                    onOpenBatterySettings = onOpenBatterySettings,
                    onOpenAppDetails = onOpenAppDetails,
                )

                TestPlaygroundCard(
                    testText = testText,
                    onTestTextChange = { testText = it },
                    testPassword = testPassword,
                    onTestPasswordChange = { testPassword = it },
                )

                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun HeroStatusCard(snapshot: PermissionSnapshot) {
    val gradient = Brush.linearGradient(
        colors = listOf(VoicePurple, Color(0xFF351952)),
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("hero_status_card"),
        shape = RoundedCornerShape(24.dp),
        tonalElevation = 4.dp,
    ) {
        Column(
            modifier = Modifier
                .background(gradient)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.GraphicEq,
                        contentDescription = "VoiceFlow logosu",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp),
                    )
                }

                Column {
                    Text(
                        text = "VoiceFlow",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                    Text(
                        text = "Sistem Geneli Sesli Dikte",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.82f),
                    )
                }
            }

            val statusText = if (snapshot.allRequiredReady) {
                "Tüm izinler hazır. Herhangi bir metin alanına dokunduğunuzda kontrol klavye üstünde görünür."
            } else {
                "Başlamak için ${3 - snapshot.completedCoreCount} zorunlu izni tamamlayın (${snapshot.completedCoreCount}/3)."
            }

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color.White.copy(alpha = 0.14f),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = if (snapshot.allRequiredReady) Color(0xFF8FF0B8) else Color(0xFFFFD89A),
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White,
                    )
                }
            }

            if (!snapshot.speechAvailable) {
                Text(
                    text = "Uyarı: Bu cihazda sistem konuşma tanıma servisi algılanamadı.",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFFFFC1C1),
                )
            }
        }
    }
}

@Composable
private fun PermissionRowCard(
    icon: ImageVector,
    title: String,
    description: String,
    granted: Boolean,
    actionLabel: String,
    testTag: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(
                            if (granted) SuccessGreenContainer else WarningAmberContainer,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (granted) Icons.Filled.CheckCircle else icon,
                        contentDescription = title,
                        tint = if (granted) SuccessGreen else WarningAmber,
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                if (granted) {
                    FilledTonalButton(
                        onClick = onClick,
                        modifier = Modifier
                            .height(48.dp)
                            .testTag(testTag),
                    ) {
                        Text(text = actionLabel)
                    }
                } else {
                    Button(
                        onClick = onClick,
                        colors = ButtonDefaults.buttonColors(containerColor = VoicePurple),
                        modifier = Modifier
                            .height(48.dp)
                            .testTag(testTag),
                    ) {
                        Text(text = actionLabel)
                    }
                }
            }
        }
    }
}

@Composable
private fun XiaomiOptimizationCard(
    snapshot: PermissionSnapshot,
    onOpenXiaomiAutostart: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onOpenAppDetails: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = if (snapshot.isXiaomiFamily) {
                    "Xiaomi / HyperOS Arka Plan Kısayolları"
                } else {
                    "Pil ve Arka Plan Kısayolları (Xiaomi / HyperOS)"
                },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Sistemin erişilebilirlik servisini kapatmaması için Otomatik Başlatma'yı açın ve pil tasarrufunu 'Kısıtlama yok' olarak ayarlayın. Erişilebilirlik anahtarı kilitliyse Uygulama Bilgileri'nden 'Kısıtlı ayarlara izin ver' seçeneğini açın.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onOpenXiaomiAutostart,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("xiaomi_autostart_button"),
                ) {
                    Icon(
                        imageVector = Icons.Filled.RocketLaunch,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "Otomatik Başlat", maxLines = 1)
                }

                OutlinedButton(
                    onClick = onOpenBatterySettings,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("battery_optimization_button"),
                ) {
                    Icon(
                        imageVector = Icons.Filled.BatteryChargingFull,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (snapshot.batteryUnrestricted) "Pil: Sınırsız" else "Pil Ayarı",
                        maxLines = 1,
                    )
                }
            }

            OutlinedButton(
                onClick = onOpenAppDetails,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("app_details_button"),
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "Uygulama Bilgileri (Kısıtlı Ayarlar)")
            }
        }
    }
}

@Composable
private fun TestPlaygroundCard(
    testText: String,
    onTestTextChange: (String) -> Unit,
    testPassword: String,
    onTestPasswordChange: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Hızlı Test Alanı",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "İzinleri açtıktan sonra aşağıdaki metin kutusuna dokunarak dikte kontrolünü doğrudan deneyebilirsiniz.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = testText,
                onValueChange = onTestTextChange,
                label = { Text("Deneme Metin Alanı") },
                placeholder = { Text("Buraya dokunun ve konuşmayı deneyin...") },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("test_text_input"),
                minLines = 2,
                maxLines = 4,
            )

            OutlinedTextField(
                value = testPassword,
                onValueChange = onTestPasswordChange,
                label = { Text("Parola Alanı Testi (Gizli Kalır)") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.Lock,
                        contentDescription = "Parola alanı",
                    )
                },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("test_password_input"),
                singleLine = true,
            )
        }
    }
}
