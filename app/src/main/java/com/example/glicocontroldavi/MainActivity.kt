package com.example.glicocontroldavi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.glicocontroldavi.ui.theme.GlicocontroldaviTheme
import kotlinx.coroutines.launch
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

// --- CONFIGURAÇÃO DO RETROFIT ---
// Troque "192.168.1.X" pelo IP IPv4 do seu computador na rede Wi-Fi
val retrofit = Retrofit.Builder()
    .baseUrl("http://192.168.1.31:8000/")
    .addConverterFactory(GsonConverterFactory.create())
    .build()

val ApiService = retrofit.create(ApiService::class.java)
// --------------------------------

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GlicocontroldaviTheme {
                var telaAtual by remember { mutableIntStateOf(0) }

                if (telaAtual == 0) {
                    TelaPrincipal(onIrParaRegistro = { telaAtual = 1 })
                } else {
                    TelaRegistro(onVoltar = { telaAtual = 0 })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TelaPrincipal(onIrParaRegistro: () -> Unit) {
    val corFundo = Color(0xFFF7FAFC)
    val corPrimaria = Color(0xFF3182CE)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("D.A.V.I.", fontWeight = FontWeight.Bold, color = corPrimaria) },
                navigationIcon = { IconButton(onClick = { }) { Icon(Icons.Default.Menu, "Menu") } },
                actions = { IconButton(onClick = { }) { Icon(Icons.Default.Notifications, "Notificações") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = corFundo)
            )
        },
        bottomBar = {
            BottomAppBar(
                containerColor = Color.White,
                content = {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        IconButton(onClick = { }) { Icon(Icons.Default.Home, "Início", tint = corPrimaria) }
                        IconButton(onClick = { }) { Icon(Icons.Default.DateRange, "Histórico") }
                        FloatingActionButton(onClick = onIrParaRegistro, containerColor = corPrimaria, shape = CircleShape) {
                            Icon(Icons.Default.Add, "Adicionar", tint = Color.White)
                        }
                        IconButton(onClick = { }) { Icon(Icons.Default.Settings, "Configurações") }
                        IconButton(onClick = { }) { Icon(Icons.Default.Person, "Perfil") }
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues).background(corFundo).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Box(modifier = Modifier.size(50.dp).clip(CircleShape).background(Color.LightGray))
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text("Olá, Davi!", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("Como você está hoje?", fontSize = 14.sp, color = Color.Gray)
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Card(modifier = Modifier.weight(1f).height(120.dp), colors = CardDefaults.cardColors(containerColor = Color.White), elevation = CardDefaults.cardElevation(2.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Última Glicose", color = Color.Gray, fontSize = 12.sp)
                        Spacer(modifier = Modifier.weight(1f))
                        Text("105", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = corPrimaria)
                        Text("mg/dL", fontSize = 12.sp, color = Color.Gray)
                    }
                }
                Card(modifier = Modifier.weight(1f).height(120.dp), colors = CardDefaults.cardColors(containerColor = Color.White), elevation = CardDefaults.cardElevation(2.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Próx. Insulina", color = Color.Gray, fontSize = 12.sp)
                        Spacer(modifier = Modifier.weight(1f))
                        Text("12:30", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        Text("Almoço", fontSize = 12.sp, color = Color.Gray)
                    }
                }
            }
            Spacer(modifier = Modifier.height(32.dp))
            Text("Ações Rápidas", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.DarkGray)
            Spacer(modifier = Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                BotaoAcaoRapida(modifier = Modifier.weight(1f).clickable { onIrParaRegistro() }, titulo = "Anotar", icone = Icons.Default.Create, corFundo = Color(0xFFBEE3F8), corIcone = Color(0xFF2B6CB0))
                BotaoAcaoRapida(modifier = Modifier.weight(1f), titulo = "Histórico", icone = Icons.Default.DateRange, corFundo = Color(0xFFB2F5EA), corIcone = Color(0xFF285E61))
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                BotaoAcaoRapida(modifier = Modifier.weight(1f), titulo = "Relatórios", icone = Icons.Default.Info, corFundo = Color(0xFFFEEBC8), corIcone = Color(0xFFC05621))
                BotaoAcaoRapida(modifier = Modifier.weight(1f), titulo = "Alarme", icone = Icons.Default.Warning, corFundo = Color(0xFFFED7D7), corIcone = Color(0xFFC53030))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TelaRegistro(onVoltar: () -> Unit) {
    var valorGlicose by remember { mutableStateOf("") }
    var momentoSelecionado by remember { mutableStateOf("") }
    val corPrimaria = Color(0xFF3182CE)

    // Variáveis para controlar a chamada da IA
    val coroutineScope = rememberCoroutineScope()
    var carregando by remember { mutableStateOf(false) }
    var mostrarAlerta by remember { mutableStateOf(false) }
    var respostaDaIA by remember { mutableStateOf("") }

    BackHandler { onVoltar() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Nova Medição", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onVoltar) { Icon(Icons.Default.ArrowBack, "Voltar") }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier.fillMaxSize().padding(paddingValues).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Qual o nível de glicose agora?", fontSize = 18.sp, color = Color.Gray)
            Spacer(modifier = Modifier.height(24.dp))

            OutlinedTextField(
                value = valorGlicose,
                onValueChange = { valorGlicose = it },
                label = { Text("mg/dL") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(32.dp))
            Text("Momento da Medição:", modifier = Modifier.align(Alignment.Start), fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(16.dp))

            val momentos = listOf("Jejum", "Pré-Refeição", "Pós-Refeição", "Antes de Dormir")
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                momentos.forEach { momento ->
                    val estaSelecionado = momento == momentoSelecionado
                    Button(
                        onClick = { momentoSelecionado = momento },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (estaSelecionado) corPrimaria else Color(0xFFEDF2F7),
                            contentColor = if (estaSelecionado) Color.White else Color.Black
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(momento)
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Botão Salvar Modificado
            Button(
                onClick = {
                    if (valorGlicose.isNotEmpty() && momentoSelecionado.isNotEmpty()) {
                        carregando = true
                        coroutineScope.launch {
                            try {
                                val medicao = Medicao(valorGlicose.toInt(), momentoSelecionado)
                                val response = apiService.enviarMedicao(medicao)

                                if (response.isSuccessful && response.body() != null) {
                                    respostaDaIA = response.body()!!.IA_resposta
                                } else {
                                    respostaDaIA = "Erro: O servidor respondeu, mas algo falhou."
                                }
                            } catch (e: Exception) {
                                respostaDaIA = "Erro de conexão: Verifique se o servidor FastAPI está rodando e se o IP está correto. Detalhe: ${e.message}"
                            } finally {
                                carregando = false
                                mostrarAlerta = true
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = corPrimaria),
                shape = RoundedCornerShape(16.dp),
                enabled = !carregando // Desabilita o botão enquanto carrega
            ) {
                if (carregando) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                } else {
                    Text("SALVAR MEDIÇÃO", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }

        // Alerta que mostra a resposta do Gemini
        if (mostrarAlerta) {
            AlertDialog(
                onDismissRequest = { mostrarAlerta = false },
                title = { Text("Recomendação do D.A.V.I.", fontWeight = FontWeight.Bold) },
                text = { Text(respostaDaIA) },
                confirmButton = {
                    TextButton(onClick = {
                        mostrarAlerta = false
                        onVoltar() // Volta para a tela principal após ler
                    }) {
                        Text("Entendido", color = corPrimaria)
                    }
                }
            )
        }
    }
}

@Composable
fun BotaoAcaoRapida(modifier: Modifier = Modifier, titulo: String, icone: ImageVector, corFundo: Color, corIcone: Color) {
    Card(modifier = modifier.height(100.dp), colors = CardDefaults.cardColors(containerColor = corFundo), shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icone, contentDescription = titulo, tint = corIcone, modifier = Modifier.size(32.dp))
            Spacer(modifier = Modifier.height(8.dp))
            Text(titulo, fontWeight = FontWeight.SemiBold, color = corIcone, fontSize = 14.sp)
        }
    }
}