package com.suportex.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
fun SessionFeedbackScreen(
    onRate: (Int) -> Unit,
    onTimeout: () -> Unit
) {
    val totalMillis = 5_000L
    var remainingMillis by remember { mutableLongStateOf(totalMillis) }
    var finished by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val startAt = System.currentTimeMillis()
        while (!finished) {
            val elapsed = System.currentTimeMillis() - startAt
            val remaining = (totalMillis - elapsed).coerceAtLeast(0L)
            remainingMillis = remaining
            if (remaining <= 0L) {
                finished = true
                onTimeout()
                break
            }
            delay(50)
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(80.dp))

            Text(
                text = "Avalie seu atendimento",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(36.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                (1..5).forEach { score ->
                    IconButton(
                        onClick = {
                            if (finished) return@IconButton
                            finished = true
                            onRate(score)
                        },
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                            .border(1.dp, Color(0xFFD7DBE1), CircleShape)
                    ) {
                        Text(
                            text = "\u2606",
                            color = Color(0xFF667085),
                            fontSize = 30.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Text(
                text = "0 = totalmente insatisfeito \u2022 5 = totalmente satisfeito",
                color = Color(0xFF667085),
                textAlign = TextAlign.Center,
                fontSize = 14.sp
            )

            Spacer(Modifier.weight(1f))

            LinearProgressIndicator(
                progress = { remainingMillis.toFloat() / totalMillis.toFloat() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(999.dp)),
                color = Color(0xFFFFCB19),
                trackColor = Color(0x1A000000)
            )
        }
    }
}
