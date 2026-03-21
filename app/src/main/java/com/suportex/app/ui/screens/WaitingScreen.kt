package com.suportex.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.suportex.app.ui.theme.TextMuted

@Suppress("unused")
@Composable
fun WaitingScreen(onCancel: () -> Unit, onAccepted: () -> Unit) {
    // Dica: aqui você pode escutar um evento do backend dizendo “técnico aceitou”.
    // Por enquanto, botão de simulação.
    Surface {
        Column(
            Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(60.dp))
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text("Acionando técnico, aguarde…", fontSize = 18.sp)
            Text("Tempo médio: ~2–5 min", color = TextMuted)
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onCancel,
                colors = ButtonDefaults.buttonColors(MaterialTheme.colorScheme.error),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text("CANCELAR SOLICITAÇÃO", color = MaterialTheme.colorScheme.onSecondary)
            }
            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = onAccepted,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text("SIMULAR ACEITAÇÃO (dev)")
            }
        }
    }
}
