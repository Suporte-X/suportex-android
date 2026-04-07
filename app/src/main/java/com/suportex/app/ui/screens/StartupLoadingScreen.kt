package com.suportex.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.rememberLottieComposition
import com.suportex.app.R

private const val STARTUP_LOTTIE_ASSET_PATH = "animations/logo_de_carregamento.json"

@Composable
fun StartupLoadingScreen() {
    val composition by rememberLottieComposition(
        LottieCompositionSpec.Asset(STARTUP_LOTTIE_ASSET_PATH)
    )

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (composition != null) {
                LottieAnimation(
                    composition = composition,
                    iterations = LottieConstants.IterateForever,
                    modifier = Modifier.size(210.dp)
                )
            } else {
                Image(
                    painter = painterResource(R.drawable.ic_suportex_logo),
                    contentDescription = null,
                    modifier = Modifier.size(140.dp)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.5.dp
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Carregando dados...",
                style = MaterialTheme.typography.bodyMedium,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
