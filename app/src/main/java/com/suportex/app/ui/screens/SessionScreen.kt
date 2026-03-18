package com.suportex.app.ui.screens

import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import com.suportex.app.Conn
import com.suportex.app.data.ChatRepository
import com.suportex.app.data.model.Message
import com.suportex.app.R
import com.suportex.app.call.CallState
import com.suportex.app.call.CallDirection
import com.suportex.app.ui.session.AudioMessagePlayer
import com.suportex.app.ui.session.UploadingAudioPlaceholder
import com.suportex.app.ui.session.rememberAudioPlaybackController
import coil.compose.AsyncImage
import java.util.Locale
import java.util.UUID
import org.json.JSONObject

@Composable
fun SessionScreen(
    sessionId: String? = null,
    technicianName: String = "T\u00e9cnico",
    isSharing: Boolean,
    remoteEnabled: Boolean,
    calling: Boolean,
    callConnected: Boolean,
    callState: CallState,
    callDirection: CallDirection?,
    // << novas props para centralizar mensagens vindas da Activity
    systemMessage: String? = null,
    onSystemMessageConsumed: () -> Unit = {},
    onStartShare: () -> Unit,
    onStopShare: () -> Unit,
    onToggleRemote: (Boolean) -> Unit,
    onStartCall: () -> Unit,
    onEndCall: () -> Unit,
    onAcceptCall: () -> Unit,
    onDeclineCall: () -> Unit,
    onAudioClick: () -> Unit,
    onAttachmentClick: () -> Unit,
    isRecordingAudio: Boolean,
    onEndSupport: () -> Unit
) {
    val chat = remember { ChatRepository() }
    DisposableEffect(chat) {
        Conn.chatRepository = chat
        onDispose {
            if (Conn.chatRepository === chat) {
                Conn.chatRepository = null
            }
        }
    }
    val focus = LocalFocusManager.current
    val ctx = LocalContext.current
    val audioPlaybackController = rememberAudioPlaybackController()

    DisposableEffect(Unit) {
        onDispose {
            audioPlaybackController.stop()
        }
    }

    // Toast "deduper"
    data class ToastGuard(var last: String? = null, var at: Long = 0L)
    val toastGuard = remember { ToastGuard() }
    fun toastOnce(msg: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (msg != toastGuard.last || (now - toastGuard.at) > 1500L) {
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
            toastGuard.last = msg
            toastGuard.at = now
        }
    }

    // timer
    var seconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1_000)
            seconds++
        }
    }
    val timer = String.format(
        Locale.getDefault(),
        "%02d:%02d:%02d",
        seconds / 3600, (seconds % 3600) / 60, seconds % 60
    )

    val callIsConnected = callConnected || callState == CallState.IN_CALL
    val showIncomingCallPrompt =
        callState == CallState.INCOMING_RINGING && callDirection == CallDirection.TECH_TO_CLIENT
    val showIdleCallAction =
        !calling && callState in setOf(
            CallState.IDLE,
            CallState.ENDED,
            CallState.FAILED,
            CallState.DECLINED,
            CallState.TIMEOUT
        )
    val showConnectingState = !showIncomingCallPrompt && !callIsConnected && callState == CallState.CONNECTING

    // === Timer da ligação (só conta quando callIsConnected = true) ===
    var callSeconds by remember { mutableIntStateOf(0) }

    LaunchedEffect(callIsConnected) {
        if (callIsConnected) {
            callSeconds = 0
            while (true) {
                kotlinx.coroutines.delay(1_000)
                callSeconds++
            }
        } else {
            callSeconds = 0
        }
    }

    val callTimer = String.format(
        Locale.getDefault(),
        "%02d:%02d:%02d",
        callSeconds / 3600, (callSeconds % 3600) / 60, callSeconds % 60
    )

    var previewImageUrl by remember { mutableStateOf<String?>(null) }

    // mensagens
    var messages by remember { mutableStateOf<List<Message>>(emptyList()) }
    val pendingMessages = remember { mutableStateListOf<Message>() }
    LaunchedEffect(sessionId) {
        if (sessionId != null) {
            chat.observeMessages(sessionId).collect { incoming ->
                val deduped = incoming
                    .asReversed()
                    .associateBy { msg -> msg.id.ifBlank { "${msg.from}:${msg.createdAt}:${msg.text ?: msg.fileUrl ?: msg.audioUrl ?: ""}" } }
                    .values
                    .toList()
                    .asReversed()
                messages = deduped
                deduped.forEach { msg ->
                    Log.d("ChatDedup", "origin=firestore id=${msg.id} sessionId=$sessionId")
                }
            }
        } else {
            messages = emptyList()
            pendingMessages.clear()
        }
    }

    LaunchedEffect(messages) {
        val deliveredIds = messages.mapNotNull { it.id.takeIf(String::isNotBlank) }.toSet()
        if (deliveredIds.isNotEmpty()) {
            pendingMessages.removeAll { it.id in deliveredIds }
        }
    }

    val combinedMessages by remember {
        derivedStateOf {
            val deliveredIds = messages.map { it.id }.toSet()
            val pendings = pendingMessages.filter { it.id !in deliveredIds }
            (messages + pendings).sortedBy { it.createdAt }
        }
    }

    val pendingIds by remember {
        derivedStateOf { pendingMessages.map { it.id }.toSet() }
    }

    // paleta
    val brandYellow = Color(0xFFFFCB19)
    val brandYellowActive = Color(0xFFB98F0E)
    val infoBlue = Color(0xFF1E88E5)
    val successGreen = Color(0xFF2DD36F)
    val dangerRed = Color(0xFFE63A3A)
    val borderSoft = Color(0xFFE1E3E8)
    val cardBg = Color(0xFFFFFFFF)
    val callYellow = Color(0xFFFFE27A)
    val shareOrange = Color(0xFFFF9800)

    val remoteEffective = isSharing && remoteEnabled

    val statusColor = when {
        remoteEffective -> dangerRed
        isSharing       -> shareOrange
        callIsConnected -> callYellow             // só muda quando ACEITA
        else            -> infoBlue
    }
    val statusText = when {
        remoteEffective -> "Compartilhamento + acesso remoto"
        isSharing       -> "Tela compartilhada"
        callIsConnected -> "Em chamada"
        else            -> "Atendimento via chat"
    }

    // bolinha “Sessão Ativa” pulsando
    val pulse by rememberInfiniteTransition(label = "pulse")
        .animateFloat(
            initialValue = 0.9f,
            targetValue = 1.1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulseAnim"
        )

    // --- dimensões e cores do botão de chamada ---
    val callPillHeight = 44.dp
    val callPillRadius = 22.dp
    val endCallContainer = Color(0xFFFFE5E5) // vermelho bem claro (tonal)
    val endCallContent  = Color(0xFFD32F2F) // vermelho escuro para texto/ícone
    val timeChipBg      = Color(0xFFEDEFF2) // fundo do chip
    val timeChipText    = Color(0xFF4A4A4A) // texto do chip


    // ======= CENTRALIZAÇÃO DOS TOASTS =======
    var prevSharing by remember { mutableStateOf(isSharing) }
    var prevRemoteEffective by remember { mutableStateOf(remoteEffective) }

    // 1) Mensagens disparadas por mudanças de estado (share/remote)
    LaunchedEffect(isSharing, remoteEnabled) {
        val nowRemoteEffective = isSharing && remoteEnabled

        // começou a compartilhar
        if (!prevSharing && isSharing) {
            if (nowRemoteEffective) {
                toastOnce("Compartilhamento de tela e acesso remoto permitido")
            } else {
                toastOnce("Compartilhamento iniciado")
            }
        }

        // parou de compartilhar
        if (prevSharing && !isSharing) {
            toastOnce("Compartilhamento encerrado")
        }

        // alternou remoto durante compartilhamento
        if (isSharing && prevRemoteEffective != nowRemoteEffective) {
            if (nowRemoteEffective) toastOnce("Acesso remoto ativado")
            else toastOnce("Acesso remoto desativado")
        }

        prevSharing = isSharing
        prevRemoteEffective = nowRemoteEffective
    }

    // 2) Mensagens vindas da Activity (perm. negada, sessão indisponível, etc.)
    LaunchedEffect(systemMessage) {
        systemMessage?.let {
            toastOnce(it)
            onSystemMessageConsumed()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
            .pointerInput(Unit) { detectTapGestures { focus.clearFocus() } }
    ) {

        // === LOGO (TOPO) ===
        Image(
            painter = painterResource(id = R.drawable.ic_suportex_logo_horizontal),
            contentDescription = "Suporte X",
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(bottom = 12.dp)
                .align(Alignment.CenterHorizontally),
            contentScale = ContentScale.Fit
        )
        // === LOGO (TOPO) — FIM ===

        // CAIXA DE STATUS
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = cardBg,
            tonalElevation = 0.dp,
            shadowElevation = 2.dp,
            border = BorderStroke(1.dp, borderSoft)
        ) {
            Column(Modifier.padding(16.dp)) {

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Sessão Ativa", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(8.dp))
                        Box(
                            Modifier
                                .size(14.dp)
                                .graphicsLayer(scaleX = pulse, scaleY = pulse)
                                .clip(CircleShape)
                                .background(successGreen)
                        )
                    }
                    Row {
                        Text("Tempo: ", fontWeight = FontWeight.SemiBold)
                        Text(timer)
                    }
                }

                Spacer(Modifier.height(8.dp))

                Row {
                    Text("T\u00e9cnico: ", fontWeight = FontWeight.SemiBold)
                    Text(technicianName.ifBlank { "T\u00e9cnico" })
                }

                Spacer(Modifier.height(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Status: ", fontWeight = FontWeight.SemiBold)
                    Box(
                        Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(statusText)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // INICIAR / PARAR COMPARTILHAMENTO — mais “magro”
        Button(
            onClick = { if (isSharing) onStopShare() else onStartShare() },
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),                // 64 -> 54
            shape = RoundedCornerShape(27.dp), // 32 -> 27 (pílula mais elegante)
            colors =
                if (isSharing)
                    ButtonDefaults.buttonColors(
                        containerColor = brandYellowActive,
                        contentColor = Color.White
                    )
                else
                    ButtonDefaults.buttonColors(
                        containerColor = brandYellow,
                        contentColor = Color.Black
                    )
        ) {
            Text(if (isSharing) "PARAR COMPARTILHAMENTO" else "INICIAR COMPARTILHAMENTO", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        }

        // praticamente colado no botão de compartilhar
        Spacer(Modifier.height(2.dp))

        // SWITCH DE ACESSO REMOTO — compacto e alinhado como extensão do botão
        @OptIn(ExperimentalMaterial3Api::class)
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset(y = (-2).dp)        //puxa 2dp pra cima
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Permitir Acesso Remoto",
                    fontSize = 14.sp,
                    color = Color(0xFF6B6B6B)
                )
                Spacer(Modifier.width(6.dp))
                Switch(
                    modifier = Modifier.scale(0.9f), // deixa o switch um pouquinho menor
                    checked = remoteEnabled,
                    onCheckedChange = { enable ->
                        onToggleRemote(enable)
                        if (enable && !isSharing) {
                            // dica só quando a pessoa liga o remoto antes do compartilhamento
                            toastOnce("Inicie o compartilhamento para começar")
                        }
                        // Quando já está compartilhando, os toasts de “ativado/desativado”
                        // continuam centralizados no LaunchedEffect lá em cima.
                    },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = infoBlue,
                        checkedThumbColor = Color.White,
                        uncheckedTrackColor = Color(0xFFCBCBCB),
                        uncheckedThumbColor = Color.White
                    )
                )
            }
        }

// pode manter o espaçamento seguinte como estava, ou reduzir um pouco se quiser
        Spacer(Modifier.height(10.dp))

        if (showIncomingCallPrompt) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = Color(0xFFFFF7E0),
                tonalElevation = 0.dp,
                shadowElevation = 1.dp,
                border = BorderStroke(1.dp, borderSoft)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("Chamada recebida", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = onAcceptCall,
                            modifier = Modifier.weight(1f).height(42.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = successGreen,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(21.dp)
                        ) {
                            Text("ACEITAR")
                        }
                        Button(
                            onClick = onDeclineCall,
                            modifier = Modifier.weight(1f).height(42.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = dangerRed,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(21.dp)
                        ) {
                            Text("RECUSAR")
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
        }

        // BOTÃO DE CHAMADA — três estados: parado / chamando / conectado (com chip de tempo)
        if (!showIncomingCallPrompt) when {
            // 1) NÃO EM CHAMADA
            showIdleCallAction -> {
                OutlinedButton(
                    onClick = onStartCall,
                    enabled = sessionId != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(callPillHeight),
                    shape = RoundedCornerShape(callPillRadius),
                    border = BorderStroke(1.dp, borderSoft),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color.White,
                        contentColor = infoBlue
                    )
                ) {
                    Icon(Icons.Default.Phone, contentDescription = null, tint = infoBlue)
                    Spacer(Modifier.width(8.dp))
                    Text("REALIZAR CHAMADA", fontWeight = FontWeight.SemiBold)
                }
            }

            // 2) EM CHAMADA, AINDA CONECTANDO (técnico não atendeu)
            showConnectingState -> {
                Button(
                    onClick = onEndCall, // permite cancelar enquanto chama
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(callPillHeight),
                    shape = RoundedCornerShape(callPillRadius),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFEFF3FF),
                        contentColor = infoBlue
                    ),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
                ) {
                    Icon(Icons.Default.Phone, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("CONECTANDO...", fontWeight = FontWeight.SemiBold)
                }
            }

            // 3) EM CHAMADA E CONECTADO — botão + chip de tempo
            callIsConnected -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Botão "Finalizar"
                    Button(
                        onClick = onEndCall,
                        modifier = Modifier
                            .weight(1f)
                            .height(callPillHeight),
                        shape = RoundedCornerShape(callPillRadius),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = endCallContainer,
                            contentColor = endCallContent
                        ),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
                    ) {
                        Icon(Icons.Default.Phone, contentDescription = null, tint = endCallContent)
                        Spacer(Modifier.width(8.dp))
                        Text("FINALIZAR CHAMADA", fontWeight = FontWeight.SemiBold)
                    }

                    Spacer(Modifier.width(6.dp))

                    // Chip do tempo
                    Surface(
                        modifier = Modifier.height(callPillHeight),
                        shape = RoundedCornerShape(callPillRadius),
                        color = timeChipBg,
                        tonalElevation = 0.dp,
                        shadowElevation = 0.dp,
                        border = BorderStroke(1.dp, borderSoft)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(callTimer, color = timeChipText, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            else -> {
                Button(
                    onClick = onEndCall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(callPillHeight),
                    shape = RoundedCornerShape(callPillRadius),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFEFF3FF),
                        contentColor = infoBlue
                    ),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
                ) {
                    Icon(Icons.Default.Phone, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("CHAMANDO...", fontWeight = FontWeight.SemiBold)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // CHAT — agora branco e com o input embutido
        Surface(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = cardBg, // <<< branco (mesmo do card de status)
            tonalElevation = 0.dp,
            shadowElevation = 1.dp,
            border = BorderStroke(1.dp, borderSoft)
        ) {
            // estado do input fica aqui dentro do card
            var input by remember { mutableStateOf("") }

            Column(Modifier.fillMaxSize()) {

                // Lista de mensagens
                val listState = rememberLazyListState()
                val uiMessages = remember(combinedMessages) { combinedMessages.asReversed() } // novas embaixo

                LazyColumn(
                    state = listState,
                    reverseLayout = true, // âncora embaixo
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp, alignment = Alignment.Bottom) // << fixa no fundo
                ) {
                    items(
                        uiMessages,
                        key = { message ->
                            val id = message.id
                            id.ifBlank { "${message.createdAt}-${message.hashCode()}" }
                        }
                    ) { m ->   // << usa uiMessages aqui
                        val isOutgoing = m.from == "client" // você -> amarelo
                        val bubbleColor =
                            if (isOutgoing) Color(0xFFFFF4C1) else Color(0xFFF1F3F6)
                        val align =
                            if (isOutgoing) Arrangement.End else Arrangement.Start
                        val isPending = pendingIds.contains(m.id)
                        val bubbleModifier = if (isPending) Modifier.alpha(0.6f) else Modifier

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = align
                        ) {
                            Surface(
                                modifier = bubbleModifier,
                                shape = RoundedCornerShape(16.dp),
                                color = bubbleColor,
                                tonalElevation = 0.dp,
                                shadowElevation = 0.dp
                            ) {
                                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                    val messageType = m.type.lowercase()
                                    val hasText = !m.text.isNullOrBlank()
                                    val hasAudio = !m.audioUrl.isNullOrBlank()
                                    val hasFile = !m.fileUrl.isNullOrBlank()

                                    when {
                                        messageType == "audio" && hasAudio -> {
                                            AudioMessagePlayer(
                                                audioUrl = m.audioUrl!!,
                                                controller = audioPlaybackController
                                            )
                                            if (hasText) Text(m.text!!)
                                        }

                                        messageType == "audio" -> {
                                            UploadingAudioPlaceholder()
                                            if (hasText) Text(m.text!!)
                                        }

                                        messageType == "image" && hasFile -> {
                                            Text(
                                                text = "📷 Imagem",
                                                color = infoBlue,
                                                modifier = Modifier.clickable {
                                                    previewImageUrl = m.fileUrl
                                                }
                                            )
                                            if (hasText) Text(m.text!!)
                                        }

                                        messageType == "file" && hasFile -> {
                                            Text("📎 Anexo", color = infoBlue)
                                            if (hasText) Text(m.text!!)
                                        }

                                        else -> {
                                            if (hasText) Text(m.text!!)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                LaunchedEffect(uiMessages.size) {
                    if (uiMessages.isNotEmpty()) listState.animateScrollToItem(0)
                }

                // --- Pílulas do input e do Enviar ---
                val inputPillHeight = 48.dp
                val inputPillRadius = 24.dp

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = inputPillHeight, max = 140.dp)   // cresce, mas com limite
                            .clip(RoundedCornerShape(inputPillRadius)),       // clip igual ao shape
                        placeholder = { Text("Mensagem…") },

                        // MULTILINHA
                        singleLine = false,
                        minLines = 1,
                        maxLines = 4,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default), // mostra Enter

                        // pílula perfeita
                        shape = RoundedCornerShape(inputPillRadius),

                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = onAudioClick, enabled = sessionId != null) {
                                    Icon(
                                        Icons.Default.Mic,
                                        contentDescription = "Áudio",
                                        tint = if (isRecordingAudio) dangerRed else LocalContentColor.current
        )

                                }
                                IconButton(onClick = onAttachmentClick, enabled = sessionId != null) {
                                    Icon(Icons.Default.AttachFile, contentDescription = "Anexo")
                                }
                            }
                        },

                        colors = OutlinedTextFieldDefaults.colors(
                            // BORDA VISÍVEL: cinza quando sem foco, amarela quando focado
                            focusedBorderColor = brandYellow,
                            unfocusedBorderColor = borderSoft,
                            disabledBorderColor = borderSoft,
                            errorBorderColor = MaterialTheme.colorScheme.error,
                            // fundo clarinho para destacar do branco do card
                            focusedContainerColor = Color(0xFFF6F7FA),
                            unfocusedContainerColor = Color(0xFFF6F7FA),

                            cursorColor = Color.Black
                        )
                    )

                    Button(
                        onClick = {
                            if (sessionId != null && input.isNotBlank()) {
                                val trimmed = input.trim()
                                val messageId = UUID.randomUUID().toString()
                                val pending = Message(
                                    id = messageId,
                                    from = "client",
                                    text = trimmed,
                                    createdAt = System.currentTimeMillis()
                                )
                                pendingMessages.removeAll { it.id == pending.id }
                                pendingMessages.add(pending)
                                val payload = JSONObject().apply {
                                    put("sessionId", sessionId)
                                    put("from", "client")
                                    put("id", messageId)
                                    put("text", trimmed)
                                }
                                Log.d("ChatDedup", "origin=android-send id=$messageId sessionId=$sessionId")
                                Conn.socket?.emit("session:chat:send", payload)
                                input = ""
                                focus.clearFocus()
                            } else if (sessionId == null) {
                                toastOnce("Sessão ainda não aceita pelo técnico.")
                            }
                        },
                        enabled = sessionId != null && input.isNotBlank(),
                        modifier = Modifier.height(inputPillHeight),
                        shape = RoundedCornerShape(inputPillRadius),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = brandYellow,
                            contentColor = Color.Black
                        ),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
                    ) {
                        Text("Enviar", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        if (!previewImageUrl.isNullOrBlank()) {
            ImagePreviewDialog(
                imageUrl = previewImageUrl!!,
                onDismiss = { previewImageUrl = null }
            )
        }

        Spacer(Modifier.height(14.dp))

        // ENCERRAR SUPORTE
        Button(
            onClick = onEndSupport,
            modifier = Modifier.fillMaxWidth().height(64.dp),
            shape = RoundedCornerShape(32.dp),
            colors = ButtonDefaults.buttonColors(containerColor = dangerRed, contentColor = Color.White)
        ) { Text("ENCERRAR SUPORTE") }
    }
}

@Composable
private fun ImagePreviewDialog(
    imageUrl: String,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offsetX by remember { mutableFloatStateOf(0f) }
        var offsetY by remember { mutableFloatStateOf(0f) }
        val transformState = rememberTransformableState { zoomChange, panChange, _ ->
            scale = (scale * zoomChange).coerceIn(1f, 4f)
            offsetX += panChange.x
            offsetY += panChange.y
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            AsyncImage(
                model = imageUrl,
                contentDescription = "Preview da imagem",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .transformable(state = transformState)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offsetX
                        translationY = offsetY
                    }
            )

            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Fechar preview",
                    tint = Color.White
                )
            }
        }
    }
}
