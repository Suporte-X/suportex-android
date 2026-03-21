package com.suportex.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
    onBlockedSupportRequest: () -> Unit,
    onOpenPurchase: () -> Unit,
    onOpenHelp: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenTerms: () -> Unit,
    textMuted: Color
) {
    var expandedPlans by rememberSaveable { mutableStateOf(false) }
    val clientName = homeSnapshot.client?.name
    val isRegisteredClient = homeSnapshot.isRegisteredClient
    val supportBlockedByCredits = homeSnapshot.isRegisteredWithoutCredit
    val firstSupportText = if (homeSnapshot.freeFirstSupportPending) {
        "Primeiro atendimento grátis disponível"
    } else {
        "Primeiro atendimento grátis já utilizado"
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(48.dp))
        Image(
            painter = painterResource(R.drawable.ic_suportex_logo),
            contentDescription = null,
            modifier = Modifier.size(180.dp)
        )
        Spacer(Modifier.height(72.dp))
        Button(
            onClick = {
                if (supportBlockedByCredits) {
                    onBlockedSupportRequest()
                } else {
                    onRequestSupport()
                }
            },
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
            Text("SOLICITAR SUPORTE", fontWeight = FontWeight.Bold)
        }

        if (isRegisteredClient) {
            Spacer(Modifier.height(14.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                tonalElevation = 1.dp
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "Créditos disponíveis: ${homeSnapshot.creditsAvailable} atendimentos",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Icon(
                            imageVector = if (expandedPlans) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = if (expandedPlans) "Fechar planos" else "Abrir planos",
                            modifier = Modifier
                                .clickable { expandedPlans = !expandedPlans }
                                .padding(2.dp)
                        )
                    }

                    if (!clientName.isNullOrBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Cliente: $clientName",
                            style = MaterialTheme.typography.bodySmall,
                            color = textMuted
                        )
                    }

                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = firstSupportText,
                        style = MaterialTheme.typography.bodySmall,
                        color = textMuted
                    )

                    if (expandedPlans) {
                        Spacer(Modifier.height(10.dp))
                        CreditPackagesTable(packages = homeSnapshot.packages)
                        Spacer(Modifier.height(8.dp))
                        TextButton(
                            onClick = onOpenPurchase,
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text("Comprar mais créditos")
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "Tempo médio de atendimento: 2-5 min",
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
                .height(52.dp)
        ) {
            Text("Pagar com PIX")
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = onBuyWhatsapp,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF168A44))
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
