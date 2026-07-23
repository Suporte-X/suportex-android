package com.suportex.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.suportex.app.R
import com.suportex.app.data.model.ClientHomeSnapshot
import com.suportex.app.data.model.CreditPackageRecord
import java.text.NumberFormat
import java.util.Locale

@Composable
fun SupportHomeScreen(
    homeSnapshot: ClientHomeSnapshot,
    onRequestSupport: () -> Unit,
    onOpenPurchase: () -> Unit,
    onOpenHelp: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenTerms: () -> Unit,
    textMuted: Color,
    averageWaitLabel: String,
    notificationState: NotificationCenterUiState = NotificationCenterUiState.Empty,
    onNotificationAction: (ClientNotificationUi) -> Unit = {},
    onNotificationDismiss: (ClientNotificationUi) -> Unit = {}
) {
    var expandedPlans by rememberSaveable { mutableStateOf(false) }
    var showNotificationCenter by remember { mutableStateOf(false) }
    val clientName = homeSnapshot.client?.name
    val supportBlockedByCredits = homeSnapshot.isRegisteredWithoutCredit
    val freeFirstSupportPending = homeSnapshot.freeFirstSupportPending
    val creditsAvailable = homeSnapshot.creditsAvailable.coerceAtLeast(0)
    val supportsDone = (homeSnapshot.clientMeta?.totalSessions ?: homeSnapshot.client?.supportsUsed ?: 0)
        .coerceAtLeast(0)
    val creditValueColor = if (creditsAvailable == 0) Color(0xFFE63A3A) else MaterialTheme.colorScheme.onSurface
    val supportButtonLabel = if (supportBlockedByCredits) {
        "SOLICITAR SUPORTE (0 CRÉDITOS)"
    } else {
        "SOLICITAR SUPORTE"
    }

    LaunchedEffect(freeFirstSupportPending) {
        if (freeFirstSupportPending) {
            expandedPlans = false
        }
    }

    BackHandler(enabled = showNotificationCenter || expandedPlans) {
        if (showNotificationCenter) {
            showNotificationCenter = false
        } else {
            expandedPlans = false
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
        Spacer(Modifier.height(48.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            if (freeFirstSupportPending) {
                Text(
                    "1º Atendimento Grátis",
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp
                )
            } else {
                CreditSummaryButton(
                    creditsAvailable = creditsAvailable,
                    expanded = expandedPlans,
                    creditValueColor = creditValueColor,
                    onClick = {
                        val shouldOpen = !expandedPlans
                        showNotificationCenter = false
                        expandedPlans = shouldOpen
                    },
                    modifier = Modifier.alpha(if (expandedPlans) 0f else 1f)
                )
            }
            NotificationBellButton(
                notificationState = notificationState,
                onClick = {
                    expandedPlans = false
                    showNotificationCenter = true
                }
            )
        }

        Spacer(Modifier.height(24.dp))
        Image(
            painter = painterResource(R.drawable.ic_suportex_logo),
            contentDescription = null,
            modifier = Modifier.size(180.dp)
        )
        Spacer(Modifier.height(96.dp))
        Button(
            onClick = onRequestSupport,
            enabled = !supportBlockedByCredits,
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            shape = RoundedCornerShape(20.dp),
            colors = if (supportBlockedByCredits) {
                ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFC8CDD5),
                    contentColor = Color(0xFF5E6269)
                )
            } else {
                ButtonDefaults.buttonColors()
            }
        ) {
            Text(supportButtonLabel, fontWeight = FontWeight.Bold)
        }

        if (supportBlockedByCredits) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = onOpenPurchase,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Text("Comprar créditos agora")
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            averageWaitLabel,
            color = textMuted,
            fontSize = 16.sp
        )
        Spacer(Modifier.weight(1f))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Ajuda", color = textMuted, modifier = Modifier.clickable { onOpenHelp() })
            Text("  ·  ", color = textMuted)
            Text("Privacidade", color = textMuted, modifier = Modifier.clickable { onOpenPrivacy() })
            Text("  ·  ", color = textMuted)
            Text("Termos", color = textMuted, modifier = Modifier.clickable { onOpenTerms() })
        }
            Spacer(Modifier.height(24.dp))
        }

        CreditStatusOverlay(
            visible = !freeFirstSupportPending && expandedPlans,
            creditsAvailable = creditsAvailable,
            clientName = clientName,
            supportsDone = supportsDone,
            creditValueColor = creditValueColor,
            textMuted = textMuted,
            onDismiss = { expandedPlans = false },
            onOpenPurchase = onOpenPurchase
        )

        NotificationCenterOverlay(
            visible = showNotificationCenter,
            uiState = notificationState,
            onDismiss = { showNotificationCenter = false },
            onNotificationAction = onNotificationAction,
            onNotificationDismiss = onNotificationDismiss
        )
    }
}

@Composable
private fun CreditSummaryButton(
    creditsAvailable: Int,
    expanded: Boolean,
    creditValueColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "Créditos:",
            fontWeight = FontWeight.SemiBold,
            fontSize = 17.sp
        )
        Spacer(Modifier.size(6.dp))
        Text(
            creditsAvailable.toString(),
            color = creditValueColor,
            fontWeight = FontWeight.SemiBold,
            fontSize = 17.sp
        )
        Spacer(Modifier.size(4.dp))
        Icon(
            imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = if (expanded) "Ocultar status de créditos" else "Mostrar status de créditos"
        )
    }
}

@Composable
private fun CreditStatusOverlay(
    visible: Boolean,
    creditsAvailable: Int,
    clientName: String?,
    supportsDone: Int,
    creditValueColor: Color,
    textMuted: Color,
    onDismiss: () -> Unit,
    onOpenPurchase: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        HomeAnchoredOverlay(
            visible = visible,
            onDismiss = onDismiss,
            panelAlignment = Alignment.TopStart
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 430.dp)
                    .animateContentSize(
                        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing)
                    ),
                shape = RoundedCornerShape(24.dp),
                color = Color.White,
                tonalElevation = 0.dp,
                shadowElevation = 12.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(42.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = buildAnnotatedString {
                                append("Créditos disponíveis: ")
                                withStyle(SpanStyle(color = creditValueColor)) {
                                    append(creditsAvailable.toString())
                                }
                                when {
                                    creditsAvailable == 1 -> append(" atendimento")
                                    creditsAvailable > 1 -> append(" atendimentos")
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 40.dp),
                            fontSize = 16.sp,
                            lineHeight = 20.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )

                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.align(Alignment.CenterEnd)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = "Fechar detalhes de créditos",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp)
                    ) {
                        if (!clientName.isNullOrBlank()) {
                            Text(
                                text = "Cliente: $clientName",
                                fontSize = 15.sp,
                                lineHeight = 20.sp,
                                color = textMuted
                            )
                            Spacer(Modifier.height(6.dp))
                        }

                        Text(
                            text = "Atendimentos já realizados: $supportsDone",
                            fontSize = 15.sp,
                            lineHeight = 20.sp,
                            color = textMuted
                        )
                    }

                    Spacer(Modifier.height(18.dp))
                    Button(
                        onClick = {
                            onDismiss()
                            onOpenPurchase()
                        },
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .widthIn(min = 210.dp, max = 260.dp)
                            .height(46.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 0.dp)
                    ) {
                        Text(
                            text = "Adquirir mais créditos",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        }

        AnimatedVisibility(
            visible = visible,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 24.dp, top = 48.dp),
            enter = fadeIn(animationSpec = tween(durationMillis = 160)),
            exit = fadeOut(animationSpec = tween(durationMillis = 120))
        ) {
            Box(
                modifier = Modifier.height(42.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                CreditSummaryButton(
                    creditsAvailable = creditsAvailable,
                    expanded = true,
                    creditValueColor = creditValueColor,
                    onClick = onDismiss
                )
            }
        }
    }
}

@Composable
fun PurchaseCreditsScreen(
    plans: List<CreditPackageRecord>,
    selectedPackageId: String?,
    onSelectPlan: (CreditPackageRecord) -> Unit,
    onBack: () -> Unit,
    onPayCard: () -> Unit,
    onPayPix: () -> Unit,
    onBuyWhatsapp: () -> Unit
) {
    val sortedPlans = remember(plans) { plans.sortedBy { it.displayOrder } }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
    ) {
        TextButton(onClick = onBack) { Text("Voltar") }
        Spacer(Modifier.height(8.dp))
        Text(
            "Adicionar créditos",
            fontWeight = FontWeight.Bold,
            fontSize = 24.sp
        )
        Text(
            "Escolha como deseja comprar seus atendimentos",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(18.dp))

        sortedPlans.forEach { plan ->
            val selected = plan.id == selectedPackageId
            OutlinedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp)
                    .clickable { onSelectPlan(plan) },
                border = androidx.compose.foundation.BorderStroke(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                ),
                colors = CardDefaults.outlinedCardColors(
                    containerColor = Color.White
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(plan.name, fontWeight = FontWeight.SemiBold)
                    Text(plan.priceLabel())
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onPayCard,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            Text("Pagar com cartão")
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = onPayPix,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = Color.White
            )
        ) {
            Text("Pagar com PIX")
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = onBuyWhatsapp,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = Color.White,
                contentColor = Color(0xFF168A44)
            )
        ) {
            Text("Comprar pelo WhatsApp")
        }
    }
}

@Composable
fun CardPlaceholderScreen(
    onBack: () -> Unit
) {
    PaymentPlaceholderScreen(
        title = "Pagamento com cartão",
        description = "Em breve",
        onBack = onBack
    )
}

@Composable
fun PixPlaceholderScreen(
    selectedPlan: CreditPackageRecord?,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
    ) {
        TextButton(onClick = onBack) { Text("Voltar") }
        Spacer(Modifier.height(8.dp))
        Text("Pagamento PIX", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            text = selectedPlan?.let { "Plano escolhido: ${it.name} (${it.priceLabel()})" }
                ?: "Plano não selecionado",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "Área reservada para QR Code PIX",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
                .padding(12.dp)
        ) {
            Text(
                "Área reservada para código copia e cola",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun PhoneIdentityDialog(
    show: Boolean,
    initialPhone: String,
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (phone: String, name: String?) -> Unit
) {
    if (!show) return
    var phone by remember { mutableStateOf(initialPhone) }
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Identificação rápida") },
        text = {
            Column {
                Text(
                    "Informe seu telefone para identificar seus créditos. Não precisa senha."
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Telefone") },
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Nome (opcional)") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(phone, name.ifBlank { null }) }) {
                Text("Continuar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}

@Composable
private fun CreditPackagesTable(packages: List<CreditPackageRecord>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
            .padding(10.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Plano", fontWeight = FontWeight.Bold)
            Text("Preço", fontWeight = FontWeight.Bold)
        }
        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        packages.sortedBy { it.displayOrder }.forEach { plan ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${plan.supportCount} atendimento${if (plan.supportCount > 1) "s" else ""}")
                Text(plan.priceLabel())
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun PaymentPlaceholderScreen(
    title: String,
    description: String,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
    ) {
        TextButton(onClick = onBack) { Text("Voltar") }
        Spacer(Modifier.height(8.dp))
        Text(title, fontWeight = FontWeight.Bold, fontSize = 24.sp)
        Spacer(Modifier.height(10.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 2.dp
        ) {
            Text(
                description,
                modifier = Modifier.padding(18.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun CreditPackageRecord.priceLabel(): String {
    val formatter = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("pt-BR"))
    return formatter.format(priceCents / 100.0)
}
