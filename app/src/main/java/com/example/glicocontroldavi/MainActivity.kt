package com.example.glicocontroldavi

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import com.example.glicocontroldavi.ui.theme.GlicocontroldaviTheme
import kotlinx.coroutines.launch
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.tasks.await
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException

val IP_SERVIDOR = "http://10.0.2.2:8000/"
val retrofit = Retrofit.Builder().baseUrl(IP_SERVIDOR).addConverterFactory(GsonConverterFactory.create()).build()
val apiService = retrofit.create(ApiService::class.java)

data class EstatisticasProcessadas(
    val media: Int,
    val basal: Double,
    val rapida: Double,
    val correcoesMedia: Double
)

data class EstatisticaMomento(
    val nomeMomento: String,
    val contagem: Int,
    val totalInsulina: Double,
    val totalCarbs: Int
)

data class ResumoHoje(
    val media: Int,
    val insulina: Double,
    val alvo: Int,
    val incidentesCount: Int
)

data class AlertaClinico(val tipo: String, val titulo: String, val mensagem: String)

data class PontoCorrelacao(val x: Float, val y: Float)

data class PontoCorrelacaoDuplo(
    val hora: String,
    val glicose: Float?,
    val basal: Float,
    val bolo: Float
)

data class PontoGrafico(val x: Float, val y: Float, val label: String)

data class DadosEficacia(val momento: String, val dose: Double, val queda: Int, val eficacia: Double)

enum class ChartRange { SEMANA, MES, ANO }

data class EstadoFiltro(
    val query: String = "",
    val momento: String = "Todos",
    val tipoInsulina: String = "Todos"
)

class MainViewModel : ViewModel() {
    private val _historico = MutableStateFlow<List<MedicaoCompleta>>(emptyList())
    val historico: StateFlow<List<MedicaoCompleta>> = _historico.asStateFlow()

    private val _filtros = MutableStateFlow(EstadoFiltro())
    val filtros: StateFlow<EstadoFiltro> = _filtros.asStateFlow()

    private val Context.dataStore by preferencesDataStore("configuracoes_davi")
    private val metaKey = intPreferencesKey("meta")
    private val fatorKey = intPreferencesKey("fator")

    fun setFiltro(tipo: String) {
        _filtros.value = _filtros.value.copy(tipoInsulina = tipo)
    }

    val tipoFiltro = derivedStateOf { _filtros.value.tipoInsulina } // Para compatibilidade com UI atual

    val historicoFiltrado = derivedStateOf {
        val todos = _historico.value
        val f = _filtros.value
        
        todos.filter { medicao ->
            val matchesInsulina = f.tipoInsulina == "Todos" || medicao.insulinas?.any { it.tipo.equals(f.tipoInsulina, true) } == true
            val matchesMomento = f.momento == "Todos" || medicao.momento.contains(f.momento, true)
            
            matchesInsulina && matchesMomento
        }
    }

    suspend fun obterToken(): String {
        return try {
            // Só tenta usar o Firebase se ele estiver configurado
            val user = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
            val tokenResult = user?.getIdToken(false)?.await()
            tokenResult?.token ?: "DAVI_OFFLINE_TOKEN"
        } catch (e: Exception) {
            "DAVI_OFFLINE_TOKEN"
        }
    }

    fun getMeta(context: Context): Flow<Int> = context.dataStore.data.map { it[metaKey] ?: 100 }
    fun getFator(context: Context): Flow<Int> = context.dataStore.data.map { it[fatorKey] ?: 50 }

    suspend fun salvarPerfil(context: Context, meta: Int, fator: Int) {
        context.dataStore.edit { prefs ->
            prefs[metaKey] = meta
            prefs[fatorKey] = fator
        }
    }

    fun enviarMedicao(medicao: Medicao, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            try {
                android.util.Log.d("DAVI", "Enviando medição: $medicao")
                val token = obterToken()
                val response = apiService.enviarMedicao("Bearer $token", medicao)
                if (response.isSuccessful) {
                    carregarDados() // Recarrega após enviar
                    onResult(true, "Salvo com sucesso!")
                    android.util.Log.d("DAVI", "Medição enviada com sucesso")
                } else {
                    val erroMsg = response.errorBody()?.string() ?: "Erro desconhecido"
                    onResult(false, "Erro ao salvar: ${response.code()}")
                    android.util.Log.e("DAVI", "Erro servidor (${response.code()}): $erroMsg")
                }
            } catch (e: Exception) {
                onResult(false, "Erro de rede: ${e.message}")
                android.util.Log.e("DAVI", "Falha de rede ao enviar: ${e.message}")
            }
        }
    }

    fun estimarCarboidratos(descricao: String, onResult: (Int?, String) -> Unit) {
        viewModelScope.launch {
            try {
                val token = obterToken()
                val response = apiService.estimarCarboidratos("Bearer $token", RequisicaoEstimativa(descricao))
                if (response.isSuccessful) {
                    val est = response.body()
                    onResult(est?.carboidratos, est?.justificativa ?: "Estimativa concluída!")
                } else {
                    onResult(null, "Erro na estimativa: ${response.code()}")
                }
            } catch (e: Exception) {
                onResult(null, "Erro: ${e.message}")
            }
        }
    }

    fun carregarDados() {
        viewModelScope.launch {
            try {
                android.util.Log.d("DAVI", "Iniciando carregarDados...")
                val token = obterToken()
                val response = apiService.obterHistorico("Bearer $token")
                if (response.isSuccessful) {
                    _historico.value = response.body()?.historico ?: emptyList()
                    android.util.Log.d("DAVI", "Dados carregados: ${_historico.value.size} itens")
                } else {
                    android.util.Log.e("DAVI", "Erro ao carregar: ${response.code()} ${response.errorBody()?.string()}")
                }
            } catch (e: Exception) {
                android.util.Log.e("DAVI", "Exceção ao carregar: ${e.message}")
            }
        }
    }

    fun deletarMedicao(id: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            try {
                val token = obterToken()
                val response = apiService.deletarMedicao("Bearer $token", mapOf("id" to id))
                if (response.isSuccessful) {
                    carregarDados()
                    onResult(true, "Excluído com sucesso")
                } else {
                    onResult(false, "Erro ao excluir: ${response.code()}")
                }
            } catch (e: Exception) {
                onResult(false, "Erro de rede: ${e.message}")
            }
        }
    }

    // Lógica similar ao useMemo para processar estatísticas
    fun obterEstatisticas() = derivedStateOf {
        val dados = _historico.value
        if (dados.isEmpty()) return@derivedStateOf null

        val mediaGlicemia = dados.map { it.valor }.average()
        val totalBasal = dados.sumOf { medicao -> 
            medicao.insulinas?.filter { it.tipo.contains("Basal", true) }?.sumOf { it.dose } ?: 0.0
        }
        val totalRapida = dados.sumOf { medicao -> 
            medicao.insulinas?.filter { it.tipo.contains("Rápida", true) }?.sumOf { it.dose } ?: 0.0
        }
        
        EstatisticasProcessadas(
            media = mediaGlicemia.toInt(),
            basal = totalBasal,
            rapida = totalRapida,
            correcoesMedia = dados.filter { it.valor > 150 }.size.toDouble() / (if (dados.size > 0) dados.size / 4.0 else 1.0)
        )
    }

    fun solicitarAnaliseIA(onResult: (String) -> Unit) {
        val dadosParaIA = _historico.value.take(30)

        // Envia esses dados para o seu servidor Python que chama o Gemini
        viewModelScope.launch {
            try {
                val token = obterToken()
                val response = apiService.analisarPadroes("Bearer $token", dadosParaIA)
                if (response.isSuccessful) {
                    onResult(response.body()?.analiseMarkdown ?: "Análise indisponível no momento.")
                } else {
                    onResult("Erro no servidor: ${response.code()}")
                }
            } catch (e: Exception) {
                onResult("Erro ao gerar análise: ${e.message}")
            }
        }
    }

    fun obterResumoDeHoje() = derivedStateOf {
        val hoje = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            java.time.LocalDate.now().toString()
        } else {
            "" // Fallback ou lógica simplificada para versões antigas
        }
        val leiturasHoje = _historico.value.filter { it.data?.contains(hoje) == true }
        
        val glicoseReadings = leiturasHoje.filter { it.valor > 0 }
        val totalInsulina = leiturasHoje.sumOf { medicao -> 
            medicao.insulinas?.sumOf { it.dose } ?: 0.0
        }
        val totalCarbs = leiturasHoje.sumOf { it.carboidratos ?: 0 }
        val mediaGlicose = if (glicoseReadings.isNotEmpty()) glicoseReadings.map { it.valor }.average().toInt() else 0
        
        val noAlvo = glicoseReadings.count { it.valor in 70..180 }
        val percentualNoAlvo = if (glicoseReadings.isNotEmpty()) (noAlvo * 100) / glicoseReadings.size else 0
        val incidentes = leiturasHoje.count { it.isTechnicalIncident }

        ResumoHoje(
            media = mediaGlicose,
            insulina = totalInsulina,
            alvo = percentualNoAlvo,
            incidentesCount = incidentes
        )
    }

    fun avaliarAlertas() = derivedStateOf {
        val leituras = _historico.value
        if (leituras.size < 3) return@derivedStateOf null

        // 1. Padrão de Jejum (Consecutivo)
        val jejumAltoConsecutivo = verificarJejumAlto(leituras)
        if (jejumAltoConsecutivo) {
            return@derivedStateOf AlertaClinico(
                "CRÍTICO",
                "PADRÃO DE RESISTÊNCIA",
                "3 dias de Jejum Alto. Considere revisão da Basal com seu médico."
            )
        }

        // 2. Tendência de Alta
        val diasAltos = leituras.filter { it.valor >= 200 }.mapNotNull { it.data?.substring(0, 10) }.distinct()
        if (diasAltos.size >= 3) {
            return@derivedStateOf AlertaClinico(
                "TENDÊNCIA",
                "Glicemia Alta",
                "Detectamos glicemia >= 200 em ${diasAltos.size} dias diferentes."
            )
        }

        // 3. Hipoglicemia Recorrente (Mesmo Horário)
        val hiposPorMomento = leituras.filter { it.valor > 0 && it.valor < 70 }
            .groupBy { it.momento }
            .filter { it.value.size >= 3 } // Ocorreu 3 ou mais vezes no mesmo momento

        if (hiposPorMomento.isNotEmpty()) {
            val momentoCritico = hiposPorMomento.keys.first()
            return@derivedStateOf AlertaClinico(
                "CRÍTICO",
                "PADRÃO DE HIPOGLICEMIA",
                "Risco recorrente em: $momentoCritico. Revise a dose anterior ou lanche."
            )
        }

        null
    }

    fun obterDadosCorrelacao() = derivedStateOf {
        _historico.value
            .filter { it.carboidratos != null && it.valor > 0 }
            .map {
                // Retorna um objeto simples que o gráfico vai ler
                PontoCorrelacao(it.carboidratos!!.toFloat(), it.valor.toFloat())
            }
    }

    fun obterDadosTendencia() = derivedStateOf {
        _historico.value.mapIndexed { index, it ->
            PontoGrafico(index.toFloat(), it.valor.toFloat(), it.momento)
        }
    }

    fun obterDadosParaRange(range: ChartRange) = derivedStateOf {
        val agora = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) java.time.LocalDate.now() else null
        val dados = _historico.value

        val filtrados = if (agora != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            dados.filter { medicao ->
                val dataMedicao = try {
                    // Tenta parsear a data que vem do servidor (esperado YYYY-MM-DD)
                    java.time.LocalDate.parse(medicao.data)
                } catch (e: Exception) {
                    null
                }

                if (dataMedicao != null) {
                    when (range) {
                        ChartRange.SEMANA -> dataMedicao.isAfter(agora.minusDays(7)) || dataMedicao.isEqual(agora.minusDays(7))
                        ChartRange.MES -> dataMedicao.isAfter(agora.minusMonths(1)) || dataMedicao.isEqual(agora.minusMonths(1))
                        ChartRange.ANO -> dataMedicao.isAfter(agora.minusYears(1)) || dataMedicao.isEqual(agora.minusYears(1))
                    }
                } else {
                    // Se não tiver data ou falhar o parse, mostramos na visão de "Semana" por padrão ou filtramos
                    range == ChartRange.SEMANA 
                }
            }.sortedBy { it.data } // Garante ordem cronológica no gráfico
        } else dados

        filtrados.mapIndexed { index, it ->
            PontoGrafico(index.toFloat(), it.valor.toFloat(), it.momento)
        }
    }

    fun obterDadosParaGraficoCorrelacao() = derivedStateOf {
        val hoje = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            java.time.LocalDate.now().toString()
        } else ""

        _historico.value
            .filter { it.data == hoje }
            .map { medicao ->
                val totalBasal = medicao.insulinas?.filter { it.tipo.contains("Basal", true) }?.sumOf { it.dose }?.toFloat() ?: 0f
                val totalBolo = medicao.insulinas?.filter { it.tipo.contains("Rápida", true) }?.sumOf { it.dose }?.toFloat() ?: 0f
                
                PontoCorrelacaoDuplo(
                    hora = medicao.momento,
                    glicose = medicao.valor.toFloat(),
                    basal = totalBasal,
                    bolo = totalBolo
                )
            }
    }

    fun obterEstatisticasPorMomento() = derivedStateOf {
        _historico.value.groupBy { it.momento }.map { (momento, medicoes) ->
            EstatisticaMomento(
                nomeMomento = momento,
                contagem = medicoes.size,
                totalInsulina = medicoes.sumOf { medicao -> 
                    medicao.insulinas?.sumOf { it.dose } ?: 0.0
                },
                totalCarbs = medicoes.sumOf { it.carboidratos ?: 0 }
            )
        }
    }

    fun obterEficaciaInsulina() = derivedStateOf {
        val dados = _historico.value.sortedBy { it.data } // Garante ordem cronológica
        val listaEficacia = mutableListOf<DadosEficacia>()

        for (i in 0 until dados.size - 1) {
            val atual = dados[i]
            val proxima = dados[i + 1]

            // Verifica se a atual teve dose de insulina
            val doseTotal = atual.insulinas?.sumOf { it.dose } ?: 0.0
            if (doseTotal > 0.0 && atual.valor > 0 && proxima.valor > 0) {
                val queda = atual.valor - proxima.valor
                val eficacia = queda / doseTotal // mg/dL por unidade
                
                listaEficacia.add(DadosEficacia(atual.momento, doseTotal, queda, eficacia))
            }
        }
        listaEficacia
    }

    fun processarEstimativaEmLote(onProgress: (Int, Int) -> Unit, onFinish: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val token = obterToken()
                val pendentes = _historico.value.filter { it.carboidratos == null && !it.mealDescription.isNullOrBlank() }
                
                if (pendentes.isEmpty()) {
                    onFinish("Nenhum registro pendente para processar.")
                    return@launch
                }

                var sucessos = 0
                pendentes.forEachIndexed { index, medicao ->
                    val responseEst = apiService.estimarCarboidratos("Bearer $token", RequisicaoEstimativa(medicao.mealDescription!!))
                    if (responseEst.isSuccessful) {
                        val est = responseEst.body()
                        if (est != null) {
                            val medicaoAtualizada = medicao.copy(carboidratos = est.carboidratos)
                            val responseUpdate = apiService.atualizarMedicao("Bearer $token", medicaoAtualizada)
                            if (responseUpdate.isSuccessful) {
                                sucessos++
                            }
                        }
                    }
                    onProgress(index + 1, pendentes.size)
                }
                
                carregarDados() // Recarrega para ver as mudanças
                onFinish("Processamento concluído: $sucessos de ${pendentes.size} registros atualizados.")
            } catch (e: Exception) {
                onFinish("Erro no processamento: ${e.message}")
            }
        }
    }
}

@Composable
fun TelaLogin(onLoginSucesso: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Bypass do Firebase se o app não estiver inicializado
    val auth = try { com.google.firebase.auth.FirebaseAuth.getInstance() } catch(e: Exception) { null }
    var carregando by remember { mutableStateOf(false) }
    var mensagemErro by remember { mutableStateOf<String?>(null) }

    // 1. Configurar as opções do Google Sign-In
    val gso = remember {
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            // Se o R.string.default_web_client_id não existir ainda, você precisará adicionar o plugin do Google Services
            .requestIdToken("SEU_WEB_CLIENT_ID_DO_FIREBASE")
            .requestEmail()
            .build()
    }
    val googleSignInClient = remember { GoogleSignIn.getClient(context, gso) }

    // 2. Launcher para o Intent do Google
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            val credential = GoogleAuthProvider.getCredential(account.idToken, null)
            
            scope.launch {
                carregando = true
                try {
                    val result = auth?.signInWithCredential(credential)?.await()
                    if (result?.user != null) {
                        onLoginSucesso()
                    } else if (auth == null) {
                        // Se não tem Firebase, deixa passar de qualquer jeito nos testes
                        onLoginSucesso()
                    }
                } catch (e: Exception) {
                    mensagemErro = "Erro Firebase: ${e.message}"
                } finally {
                    carregando = false
                }
            }
        } catch (e: ApiException) {
            mensagemErro = "Erro Google: ${e.statusCode}"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF7FAFC))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(color = Color(0xFF10B981), shape = CircleShape, modifier = Modifier.size(80.dp)) {
            Icon(Icons.Default.Favorite, "Logo", tint = Color.White, modifier = Modifier.padding(16.dp))
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text("Bem-vindo ao D.A.V.I.", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("Diário de Acompanhamento Vigilante de Insulino-dependência", fontSize = 14.sp, color = Color.Gray, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        
        Spacer(modifier = Modifier.height(48.dp))

        if (carregando) {
            CircularProgressIndicator(color = Color(0xFF3182CE))
        } else {
            Button(
                onClick = { 
                    mensagemErro = null
                    launcher.launch(googleSignInClient.signInIntent) 
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Color.LightGray),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Aqui você poderia colocar o ícone real do Google
                    Icon(Icons.Default.AccountCircle, null, tint = Color.Gray)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("Entrar com Google", color = Color.DarkGray, fontWeight = FontWeight.Bold)
                }
            }
        }

        mensagemErro?.let {
            Spacer(modifier = Modifier.height(16.dp))
            Text(it, color = Color.Red, fontSize = 12.sp)
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
    /* Temporariamente desativado para facilitar depuração
    Thread.setDefaultUncaughtExceptionHandler { _, _ ->
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        android.os.Process.killProcess(android.os.Process.myPid())
        System.exit(2)
    }
    */

        setContent {
            val viewModel: MainViewModel = viewModel()
            // Temporariamente ignorando o Firebase para o app abrir no seu celular
            // val auth = FirebaseAuth.getInstance()
            var usuarioLogado by remember { mutableStateOf(true) }

            LaunchedEffect(usuarioLogado) {
                if (usuarioLogado) viewModel.carregarDados()
            }
            
            GlicocontroldaviTheme {
                if (!usuarioLogado) {
                    TelaLogin(onLoginSucesso = { usuarioLogado = true })
                } else {
                    var telaAtual by remember { mutableIntStateOf(0) }
                    var medicaoParaEditar by remember { mutableStateOf<MedicaoCompleta?>(null) }
                    val corPrimaria = Color(0xFF3182CE)

                    Scaffold(
                        topBar = {
                            @OptIn(ExperimentalMaterial3Api::class)
                            TopAppBar(
                                title = { 
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Surface(color = Color(0xFF10B981), shape = CircleShape, modifier = Modifier.size(32.dp)) {
                                            Icon(Icons.Default.Favorite, "Logo", tint = Color.White, modifier = Modifier.padding(6.dp))
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("D.A.V.I.", fontWeight = FontWeight.Bold)
                                    }
                                },
                                actions = {
                                    Text(
                                        text = "Davi", // Nome fixo temporário
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = Color.Gray,
                                        modifier = Modifier.padding(end = 8.dp)
                                    )
                                    IconButton(onClick = { 
                                        // auth.signOut()
                                        usuarioLogado = false
                                    }) {
                                        Icon(Icons.Default.Logout, "Sair")
                                    }
                                },
                                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFFF7FAFC))
                            )
                        },
                        bottomBar = {
                            BottomAppBar(
                                containerColor = Color.White,
                                content = {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                        IconButton(onClick = { telaAtual = 0 }) { Icon(Icons.Default.Home, "Início", tint = if (telaAtual == 0) corPrimaria else Color.Gray) }
                                        IconButton(onClick = { telaAtual = 2 }) { Icon(Icons.Default.DateRange, "Histórico", tint = if (telaAtual == 2) corPrimaria else Color.Gray) }
                                        FloatingActionButton(onClick = { telaAtual = 1 }, containerColor = corPrimaria, shape = CircleShape) {
                                            Icon(Icons.Default.Add, "Adicionar", tint = Color.White)
                                        }
                                        IconButton(onClick = { telaAtual = 3 }) { Icon(Icons.Default.Settings, "Configurações", tint = if (telaAtual == 3) corPrimaria else Color.Gray) }
                                        IconButton(onClick = { telaAtual = 4 }) { Icon(Icons.Default.Person, "Perfil", tint = if (telaAtual == 4) corPrimaria else Color.Gray) }
                                    }
                                }
                            )
                        }
                    ) { paddingValues ->
                        Box(modifier = Modifier.padding(paddingValues)) {
                            when (telaAtual) {
                                0 -> TelaPrincipal(viewModel, { 
                                    medicaoParaEditar = null
                                    telaAtual = 1 
                                }, { telaAtual = it })
                                1 -> TelaRegistro(
                                    viewModel, 
                                    onVoltar = { telaAtual = 0 }, 
                                    medicaoEdicao = medicaoParaEditar,
                                    onSucesso = { 
                                        medicaoParaEditar = null
                                        viewModel.carregarDados() 
                                    }
                                )
                                2 -> TelaHistorico(
                                    viewModel,
                                    onVoltar = { telaAtual = 0 },
                                    onEditar = { medicao ->
                                        medicaoParaEditar = medicao
                                        telaAtual = 1
                                    }
                                )
                                3 -> TelaConfiguracoes(viewModel) { telaAtual = 0 }
                                4 -> TelaPerfil(viewModel) { telaAtual = 0 }
                                5 -> TelaAnalises(viewModel) { telaAtual = 0 }
                                6 -> TelaAnaliseIA(viewModel) { telaAtual = 0 }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TelaErroFallback(onTentarNovamente: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.Error, contentDescription = null, tint = Color.Red, modifier = Modifier.size(64.dp))
        Spacer(modifier = Modifier.height(16.dp))
        Text("Ops! Algo deu errado.", color = Color.Red, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = onTentarNovamente) { Text("Tentar novamente") }
    }
}

@Preview(showBackground = true, name = "Registro Vazio")
@Composable
fun PreviewTelaRegistro() {
    GlicocontroldaviTheme {
        TelaRegistro(viewModel(), onVoltar = {})
    }
}

@Preview(showBackground = true, name = "Registro com Sugestão")
@Composable
fun PreviewTelaRegistroComSugestao() {
    GlicocontroldaviTheme {
        TelaRegistro(viewModel(), onVoltar = {}, inicialGlicose = "180")
    }
}

@Preview(showBackground = true, name = "Histórico Detalhado")
@Composable
fun PreviewTelaHistorico() {
    GlicocontroldaviTheme {
        TelaHistorico(viewModel(), onVoltar = {}, onEditar = {})
    }
}

@Preview(showBackground = true, name = "Principal com Alertas")
@Composable
fun PreviewTelaPrincipalAlertas() {
    GlicocontroldaviTheme {
        TelaPrincipal(viewModel(), onIrParaRegistro = {}, onNavegar = {})
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TelaPrincipal(viewModel: MainViewModel, onIrParaRegistro: () -> Unit, onNavegar: (Int) -> Unit) {
    val corFundo = Color(0xFFF7FAFC)
    val corPrimaria = Color(0xFF3182CE)
    val context = LocalContext.current
    val listaMedicoes by viewModel.historico.collectAsState()
    val carregando = false // Agora controlado pelo Flow ou estado interno se necessário

    // Solicitar permissão de notificação no Android 13+
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { /* Permissão concedida ou não */ }
    )

    SideEffect {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    LaunchedEffect(listaMedicoes) {
        if (listaMedicoes.isNotEmpty()) {
            processarDadosRecentes(context, listaMedicoes)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(corFundo)
            .padding(16.dp)
    ) {
        // val usuario = FirebaseAuth.getInstance().currentUser
        val nomeUsuario = "Davi" // usuario?.displayName?.split(" ")?.firstOrNull() ?: "Davi"

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.size(50.dp).clip(CircleShape).background(Color.LightGray)) {
                Icon(Icons.Default.Person, null, modifier = Modifier.padding(12.dp), tint = Color.White)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text("Olá, $nomeUsuario!", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("Aqui está o seu resumo de hoje.", fontSize = 14.sp, color = Color.Gray)
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        val alertaClinico = viewModel.avaliarAlertas().value

        if (alertaClinico != null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (alertaClinico.tipo == "CRÍTICO") Color(0xFFE53E3E) else Color(
                        0xFFDD6B20
                    )
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = Color.White)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(alertaClinico.titulo, color = Color.White, fontWeight = FontWeight.Bold)
                        Text(alertaClinico.mensagem, color = Color.White, fontSize = 12.sp)
                    }
                }
            }
        }

        if (carregando) {
            Box(modifier = Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = corPrimaria)
            }
        } else {
            val resumo = viewModel.obterResumoDeHoje().value
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                CardResumo("Média", "${resumo.media}", "mg/dL", Color(0xFF10B981)) // Esmeralda
                CardResumo("Insulina", "${resumo.insulina}", "Unidades", Color(0xFF3B82F6)) // Azul
                CardResumo("No Alvo", "${resumo.alvo}%", "hoje", Color(0xFFF59E0B)) // Âmbar
            }

            if (resumo.incidentesCount > 0) {
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF5F5))
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = Color.Red)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Atenção: ${resumo.incidentesCount} incidentes técnicos hoje. Verifique o sensor.",
                            color = Color.Red,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Cards de Alerta Clínico (High / Low)
            if (resumo.alvo < 70 && resumo.media > 0) {
                val isHigh = resumo.media > 180
                val corAlerta = if (isHigh) Color(0xFFFED7D7) else Color(0xFFFFF5F5)
                val textoAlerta = if (isHigh) "Glicemia Média Elevada" else "Risco de Hipoglicemia"
                val descAlerta = if (isHigh) "Sua média está acima de 180 mg/dL. Considere revisar as doses." else "Seu tempo no alvo está baixo. Monitore com mais frequência."

                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = corAlerta),
                    border = BorderStroke(1.dp, Color.Red.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (isHigh) Icons.Default.TrendingUp else Icons.Default.TrendingDown, contentDescription = null, tint = Color.Red)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(textoAlerta, fontWeight = FontWeight.Bold, color = Color.Red)
                        }
                        Text(descAlerta, fontSize = 12.sp, color = Color.DarkGray, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Spacer(modifier = Modifier.height(24.dp))

            var rangeSelecionado by remember { mutableStateOf(ChartRange.SEMANA) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Tendência de Glicose", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Row {
                    ChartRange.entries.forEach { range ->
                        TextButton(
                            onClick = { rangeSelecionado = range },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = if (rangeSelecionado == range) corPrimaria else Color.Gray
                            )
                        ) {
                            Text(
                                when(range) {
                                    ChartRange.SEMANA -> "7d"
                                    ChartRange.MES -> "30d"
                                    ChartRange.ANO -> "1a"
                                },
                                fontSize = 12.sp,
                                fontWeight = if (rangeSelecionado == range) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            TendenciaGlicemia(viewModel, rangeSelecionado)

            Spacer(modifier = Modifier.height(24.dp))
            
            Button(
                onClick = { onNavegar(6) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF805AD5)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Gerar Análise Profunda (IA)")
            }
        }
    }
}

@Composable
fun TendenciaGlicemia(viewModel: MainViewModel, range: ChartRange) {
    val corPrimaria = Color(0xFF3182CE)
    val pontosTendencia by viewModel.obterDadosParaRange(range)

    Card(
        modifier = Modifier.fillMaxWidth().height(200.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        if (pontosTendencia.size < 2) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Sem dados suficientes para este período", color = Color.Gray)
            }
        } else {
            androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize().padding(24.dp)) {
                val maxGlicose = 250f
                val minGlicose = 50f
                val width = size.width
                val height = size.height
                val spacing = width / (pontosTendencia.size - 1)

                val coordenadas = pontosTendencia.mapIndexed { index, ponto ->
                    val x = index * spacing
                    val y = height - ((ponto.y - minGlicose) / (maxGlicose - minGlicose) * height).toFloat()
                    androidx.compose.ui.geometry.Offset(x, y.coerceIn(0f, height))
                }

                // Desenha a linha contínua usando Path
                val path = androidx.compose.ui.graphics.Path()
                coordenadas.forEachIndexed { index, offset ->
                    if (index == 0) path.moveTo(offset.x, offset.y)
                    else path.lineTo(offset.x, offset.y)
                }

                drawPath(
                    path = path,
                    color = corPrimaria,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = 8f,
                        cap = androidx.compose.ui.graphics.StrokeCap.Round,
                        join = androidx.compose.ui.graphics.StrokeJoin.Round
                    )
                )

                // Desenha os pontos (círculos)
                coordenadas.forEach { ponto ->
                    drawCircle(
                        color = Color.White,
                        radius = 10f,
                        center = ponto
                    )
                    drawCircle(
                        color = Color(0xFF2B6CB0),
                        radius = 6f,
                        center = ponto
                    )
                }
            }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TelaHistorico(viewModel: MainViewModel, onVoltar: () -> Unit, onEditar: (MedicaoCompleta) -> Unit) {
    val listaMedicoes by viewModel.historicoFiltrado
    val filtroAtivo by viewModel.tipoFiltro
    val context = LocalContext.current
    val carregando = false
    val erro = ""

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        IconButton(onClick = onVoltar) { Icon(Icons.Default.ArrowBack, "Voltar") }
        Text("Histórico de Medições", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFF3182CE))
        
        Spacer(modifier = Modifier.height(16.dp))

        // Linha de Filtros
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("Todos", "Basal", "Rápida").forEach { tipo ->
                FilterChip(
                    selected = filtroAtivo == tipo,
                    onClick = { viewModel.setFiltro(tipo) },
                    label = { Text(tipo) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF3182CE),
                        selectedLabelColor = Color.White
                    )
                )
            }
        }

        if (carregando) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color(0xFF3182CE))
            }
        } else if (erro.isNotEmpty()) {
            Text(erro, color = Color.Red, modifier = Modifier.padding(16.dp))
        } else if (listaMedicoes.isEmpty()) {
            Text("Nenhum registro encontrado.", modifier = Modifier.padding(16.dp))
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(listaMedicoes.reversed()) { medicao ->
                    var mostrarMenu by remember { mutableStateOf(false) }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                            .clickable { mostrarMenu = true },
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        elevation = CardDefaults.cardElevation(2.dp),
                        border = if (medicao.isTechnicalIncident) BorderStroke(1.dp, Color.Red.copy(alpha = 0.5f)) else null
                    ) {
                        Box {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                    // Ícone de Glicose com cor dinâmica
                                    val corGlicose = when {
                                        medicao.valor < 70 -> Color.Red
                                        medicao.valor > 180 -> Color(0xFFED8936) // Laranja alerta
                                        else -> Color(0xFF48BB78) // Verde sucesso
                                    }
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(corGlicose)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            "${medicao.valor} mg/dL",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 18.sp
                                        )
                                        Text(
                                            "${medicao.data} • ${medicao.momento}",
                                            fontSize = 12.sp,
                                            color = Color.Gray
                                        )
                                    }
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (medicao.isAIRecord) {
                                        Icon(
                                            Icons.Default.AutoAwesome,
                                            contentDescription = "Estimado por IA",
                                            tint = Color(0xFF805AD5), // Roxo IA
                                            modifier = Modifier.size(20.dp).padding(horizontal = 4.dp)
                                        )
                                    }
                                    if (medicao.isTechnicalIncident) {
                                        Icon(
                                            Icons.Default.Warning,
                                            contentDescription = "Incidente Técnico",
                                            tint = Color.Red,
                                            modifier = Modifier.size(20.dp).padding(horizontal = 4.dp)
                                        )
                                    }
                                    if (!medicao.insulinas.isNullOrEmpty()) {
                                        Column(horizontalAlignment = Alignment.End) {
                                            medicao.insulinas?.forEach { dose ->
                                                Text(
                                                    "💉 ${dose.dose}U (${dose.tipo.take(1)})",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (dose.tipo.contains("Basal", true)) Color(0xFF2D3748) else Color(0xFF3182CE)
                                                )
                                            }
                                        }
                                    }
                                    
                                    IconButton(onClick = { mostrarMenu = true }) {
                                        Icon(Icons.Default.MoreVert, "Opções")
                                    }
                                }
                            }
                            
                            DropdownMenu(
                                expanded = mostrarMenu,
                                onDismissRequest = { mostrarMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Editar") },
                                    leadingIcon = { Icon(Icons.Default.Edit, null) },
                                    onClick = {
                                        mostrarMenu = false
                                        onEditar(medicao)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Excluir", color = Color.Red) },
                                    leadingIcon = { Icon(Icons.Default.Delete, null, tint = Color.Red) },
                                    onClick = {
                                        mostrarMenu = false
                                        medicao.id?.let { id ->
                                            viewModel.deletarMedicao(id) { sucesso, msg ->
                                                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CardResumo(titulo: String, valor: String, unidade: String, cor: Color) {
    Card(modifier = Modifier.width(100.dp), colors = CardDefaults.cardColors(containerColor = cor.copy(alpha = 0.1f))) {
        Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(titulo, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = cor)
            Text(valor, fontSize = 18.sp, fontWeight = FontWeight.Black)
            Text(unidade, fontSize = 9.sp, color = Color.Gray)
        }
    }
}

@Composable
fun TelaAnalises(viewModel: MainViewModel, onVoltar: () -> Unit) {
    val stats by viewModel.obterEstatisticas()
    val listaEficacia by viewModel.obterEficaciaInsulina()
    
    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        IconButton(onClick = onVoltar) { Icon(Icons.Default.ArrowBack, "Voltar") }
        Text("Análises Clínicas", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFF3182CE))

        Spacer(modifier = Modifier.height(24.dp))

        // Exemplo de Card de Status (substituindo o seu "EfficacyScore")
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFEBF8FF))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("STATUS BASAL", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                Text("Geral: ${if ((stats?.basal ?: 0.0) > 0) "Ativo" else "Sem dados"}", fontSize = 20.sp, fontWeight = FontWeight.Black, color = Color(0xFF3182CE))
                Text("Baseado em suas últimas medições.", fontSize = 12.sp, color = Color.Gray)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Grade de Estatísticas
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard("Correções", "${stats?.correcoesMedia ?: "--"}", "por dia", Modifier.weight(1f))
            StatCard("Basal Total", "${stats?.basal ?: "--"} U", "unidades", Modifier.weight(1f))
            StatCard("Glicemia Méd.", "${stats?.media ?: "--"}", "mg/dL", Modifier.weight(1f))
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text("Correlação: Carboidratos vs. Glicose", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(modifier = Modifier.height(8.dp))
        
        val dadosCorrelacao by viewModel.obterDadosCorrelacao()
        Card(
            modifier = Modifier.fillMaxWidth().height(250.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(2.dp)
        ) {
            if (dadosCorrelacao.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Sem dados de carboidratos para correlacionar", color = Color.Gray)
                }
            } else {
                GraficoDispersao(dadosCorrelacao)
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text("Glicose vs. Insulina (Hoje)", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(modifier = Modifier.height(8.dp))

        val dadosSobreposicao by viewModel.obterDadosParaGraficoCorrelacao()
        Card(
            modifier = Modifier.fillMaxWidth().height(250.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(2.dp)
        ) {
            if (dadosSobreposicao.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Sem medições registradas hoje", color = Color.Gray)
                }
            } else {
                GraficoSobreposicaoGlicoseInsulina(dadosSobreposicao)
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text("Resumo por Momento do Dia", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(modifier = Modifier.height(8.dp))

        val statsMomento by viewModel.obterEstatisticasPorMomento()
        if (statsMomento.isEmpty()) {
            Text("Sem dados agrupados", color = Color.Gray, fontSize = 12.sp)
        } else {
            statsMomento.sortedByDescending { it.contagem }.forEach { momento ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(1.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(momento.nomeMomento, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text("${momento.contagem} registros", fontSize = 11.sp, color = Color.Gray)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            InfoPequena("💉 ${String.format("%.1f", momento.totalInsulina)}U", Color(0xFF3182CE))
                            InfoPequena("🍞 ${momento.totalCarbs}g", Color(0xFFD69E2E))
                        }
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))

        Text("Histórico de Eficácia", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(modifier = Modifier.height(8.dp))
        
        Card(
            modifier = Modifier.fillMaxWidth().height(250.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(2.dp)
        ) {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                items(listaEficacia) { item ->
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(item.momento, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Text("Dose: ${item.dose}U", fontSize = 11.sp, color = Color.Gray)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("Queda: ${item.queda} mg/dL", fontWeight = FontWeight.Bold, color = Color(0xFF3182CE))
                                Text("Sensibilidade: ${"%.1f".format(item.eficacia)}", fontSize = 11.sp, color = Color(0xFF10B981))
                            }
                        }
                        Divider(color = Color.LightGray.copy(alpha = 0.5f))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
fun InfoPequena(texto: String, cor: Color) {
    Surface(
        color = cor.copy(alpha = 0.1f),
        shape = RoundedCornerShape(4.dp)
    ) {
        Text(
            text = texto,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = cor
        )
    }
}

@Composable
fun GraficoSobreposicaoGlicoseInsulina(pontos: List<PontoCorrelacaoDuplo>) {
    val corBasal = Color(0xFFCBD5E0)
    val corBolo = Color(0xFF3182CE)
    val corGlicose = Color.Red

    Canvas(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 24.dp)) {
        val width = size.width
        val height = size.height
        val maxGlicose = 300f
        val maxInsulina = 15f
        val spacing = if (pontos.size > 1) width / (pontos.size - 1) else width
        val barWidth = 24f

        // 1. Desenhar Barras Primeiro (Insulina) na parte inferior
        pontos.forEachIndexed { index, ponto ->
            val x = index * spacing
            
            // Basal
            if (ponto.basal > 0f) {
                val barHeight = (ponto.basal / maxInsulina) * height
                drawRect(
                    color = corBasal,
                    topLeft = Offset(x - barWidth, height - barHeight.coerceIn(0f, height)),
                    size = androidx.compose.ui.geometry.Size(barWidth, barHeight.coerceIn(0f, height))
                )
            }
            
            // Bolo
            if (ponto.bolo > 0f) {
                val barHeight = (ponto.bolo / maxInsulina) * height
                drawRect(
                    color = corBolo,
                    topLeft = Offset(x, height - barHeight.coerceIn(0f, height)),
                    size = androidx.compose.ui.geometry.Size(barWidth, barHeight.coerceIn(0f, height))
                )
            }
        }

        // 2. Desenhar Linha de Glicose com Path (Sobreposição no topo)
        val path = androidx.compose.ui.graphics.Path()
        var firstPoint = true
        
        pontos.forEachIndexed { index, ponto ->
            ponto.glicose?.let { g ->
                val x = index * spacing
                val y = height - (g / maxGlicose) * height
                val yCoerced = y.coerceIn(0f, height)
                
                if (firstPoint) {
                    path.moveTo(x, yCoerced)
                    firstPoint = false
                } else {
                    path.lineTo(x, yCoerced)
                }
            }
        }

        drawPath(
            path = path,
            color = corGlicose,
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = 8f,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                join = androidx.compose.ui.graphics.StrokeJoin.Round
            )
        )

        // Adicionar círculos nos pontos de glicose para destaque
        pontos.forEachIndexed { index, ponto ->
            ponto.glicose?.let { g ->
                val x = index * spacing
                val y = height - (g / maxGlicose) * height
                drawCircle(color = Color.White, radius = 10f, center = Offset(x, y.coerceIn(0f, height)))
                drawCircle(color = corGlicose, radius = 6f, center = Offset(x, y.coerceIn(0f, height)))
            }
        }
    }
}

@Composable
fun GraficoDispersao(pontos: List<PontoCorrelacao>) {
    Canvas(modifier = Modifier.fillMaxWidth().height(300.dp).padding(24.dp)) {
        val maxCarbs = 100f // Escala X
        val maxGlicose = 300f // Escala Y

        pontos.forEach { ponto ->
            val x = (ponto.x / maxCarbs) * size.width
            val y = size.height - (ponto.y / maxGlicose) * size.height

            drawCircle(
                color = if (ponto.y > 180) Color.Red else Color(0xFF10B981),
                radius = 12f,
                center = Offset(x.coerceIn(0f, size.width), y.coerceIn(0f, size.height))
            )
        }
    }
}

@Composable
fun StatCard(titulo: String, valor: String, legenda: String, modifier: Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(titulo, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
            Text(valor, fontSize = 18.sp, fontWeight = FontWeight.Black)
            Text(legenda, fontSize = 9.sp, color = Color.Gray)
        }
    }
}

@Composable
fun TelaConfiguracoes(viewModel: MainViewModel, onVoltar: () -> Unit) {
    var processando by remember { mutableStateOf(false) }
    var progresso by remember { mutableStateOf(0 to 0) }
    var mensagemFinal by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        IconButton(onClick = onVoltar) { Icon(Icons.Default.ArrowBack, "Voltar") }
        Text("Configurações", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFF3182CE))
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text("Preferências Visuais", fontWeight = FontWeight.Bold, color = Color.Gray)
        Text("Tema: Claro", modifier = Modifier.padding(vertical = 8.dp))
        Text("Unidade de Medida: mg/dL", modifier = Modifier.padding(vertical = 8.dp))
        
        Divider(modifier = Modifier.padding(vertical = 16.dp))
        
        Text("Processamento de Dados (IA)", fontWeight = FontWeight.Bold, color = Color.Gray)
        Spacer(modifier = Modifier.height(8.dp))
        Text("Estime carboidratos para registros antigos que possuem apenas descrição textual.", fontSize = 12.sp, color = Color.DarkGray)
        
        Spacer(modifier = Modifier.height(16.dp))
        
        if (processando) {
            LinearProgressIndicator(
                progress = { if (progresso.second > 0) progresso.first.toFloat() / progresso.second else 0f },
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFF3182CE),
            )
            Text("Processando item ${progresso.first} de ${progresso.second}...", fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        } else {
            Button(
                onClick = {
                    processando = true
                    mensagemFinal = ""
                    viewModel.processarEstimativaEmLote(
                        onProgress = { atual, total -> progresso = atual to total },
                        onFinish = { msg -> 
                            processando = false
                            mensagemFinal = msg 
                        }
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF805AD5))
            ) {
                Icon(Icons.Default.AutoAwesome, null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Processar Pendências de IA")
            }
        }
        
        if (mensagemFinal.isNotEmpty()) {
            Text(mensagemFinal, color = Color(0xFF3182CE), fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        }

        Spacer(modifier = Modifier.height(24.dp))
        Text("Notificações: Ativadas", modifier = Modifier.padding(vertical = 8.dp))
    }
}

@Composable
fun TelaPerfil(viewModel: MainViewModel, onVoltar: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val metaGlicemiaFlow = remember { viewModel.getMeta(context) }
    val fatorSensibilidadeFlow = remember { viewModel.getFator(context) }
    
    val metaGlicemiaPersistida by metaGlicemiaFlow.collectAsState(initial = 100)
    val fatorSensibilidadePersistido by fatorSensibilidadeFlow.collectAsState(initial = 50)

    var metaGlicemia by remember(metaGlicemiaPersistida) { mutableStateOf(metaGlicemiaPersistida.toString()) }
    var fatorSensibilidade by remember(fatorSensibilidadePersistido) { mutableStateOf(fatorSensibilidadePersistido.toString()) }

    Column(modifier = Modifier.padding(16.dp)) {
        IconButton(onClick = onVoltar) { Icon(Icons.Default.ArrowBack, "Voltar") }
        Text("Perfil e Metas", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(24.dp))
        OutlinedTextField(value = metaGlicemia, onValueChange = { metaGlicemia = it }, label = { Text("Meta (mg/dL)") }, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(value = fatorSensibilidade, onValueChange = { fatorSensibilidade = it }, label = { Text("Fator Sensibilidade") }, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = {
            scope.launch {
                viewModel.salvarPerfil(
                    context, 
                    metaGlicemia.toIntOrNull() ?: 100, 
                    fatorSensibilidade.toIntOrNull() ?: 50
                )
            }
        }, modifier = Modifier.fillMaxWidth()) { Text("Salvar Perfil") }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TelaRegistro(
    viewModel: MainViewModel, 
    onVoltar: () -> Unit, 
    inicialGlicose: String = "", 
    medicaoEdicao: MedicaoCompleta? = null,
    onSucesso: () -> Unit = {}
) {
    // 1. Estados Iniciais - Preenche se estiver editando
    var glicose by remember { mutableStateOf(medicaoEdicao?.valor?.toString() ?: inicialGlicose) }
    var carboidratos by remember { mutableStateOf(medicaoEdicao?.carboidratos?.toString() ?: "") }
    var comidaDescricao by remember { mutableStateOf(medicaoEdicao?.mealDescription ?: "") }
    var isErroTecnico by remember { mutableStateOf(medicaoEdicao?.isTechnicalIncident ?: false) }
    
    // Lista dinâmica de insulinas
    val listaInsulinas = remember { 
        mutableStateListOf<DoseInsulina>().apply {
            medicaoEdicao?.insulinas?.let { addAll(it) }
        }
    }
    
    var doseTemp by remember { mutableStateOf("") }
    var tipoTemp by remember { mutableStateOf("Rápida") }
    var expandirInsulina by remember { mutableStateOf(false) }

    // 2. Integração com Perfil (DataStore)
    val context = LocalContext.current
    val metaGlicemiaPersistida by viewModel.getMeta(context).collectAsState(initial = 100)
    val fatorSensibilidadePersistido by viewModel.getFator(context).collectAsState(initial = 50)

    // Cálculo Dinâmico da Dose Recomendada
    val doseRecomendada = remember(glicose, metaGlicemiaPersistida, fatorSensibilidadePersistido) {
        val v = glicose.toIntOrNull()
        if (v != null) {
            if (v > metaGlicemiaPersistida) {
                val calc = (v - metaGlicemiaPersistida) / fatorSensibilidadePersistido
                if (calc > 0) "$calc U de Rápida" else "Dentro da meta"
            } else "Dentro da meta"
        } else ""
    }

    val momentos = listOf("Jejum", "Pré-Café da Manhã", "Pós-Café da Manhã", "Colação (Lanche Manhã)", "Pré-Almoço", "Pós-Almoço", "Lanche da Tarde", "Pré-Jantar", "Pós-Jantar", "Ceia", "Ao Deitar", "Madrugada", "Outro", "Aleatório")
    var momentoSelecionado by remember { mutableStateOf(medicaoEdicao?.momento ?: momentos[0]) }
    var dropdownExpandido by remember { mutableStateOf(false) }
    var respostaServidor by remember { mutableStateOf("") }
    var estimandoCarbs by remember { mutableStateOf(false) }
    var mostrarModalChecklist by remember { mutableStateOf(false) }

    val executarEnvio = {
        if (medicaoEdicao != null) {
            // Modo Edição
            val medicaoAtualizada = medicaoEdicao.copy(
                valor = glicose.toIntOrNull() ?: 0,
                momento = momentoSelecionado,
                insulinas = listaInsulinas.toList(),
                carboidratos = carboidratos.toIntOrNull(),
                isTechnicalIncident = isErroTecnico,
                mealDescription = comidaDescricao.ifBlank { null }
            )
            
            viewModel.viewModelScope.launch {
                try {
                    val token = viewModel.obterToken()
                    val response = apiService.atualizarMedicao("Bearer $token", medicaoAtualizada)
                    if (response.isSuccessful) {
                        onSucesso()
                        onVoltar()
                    } else {
                        respostaServidor = "Erro ao atualizar: ${response.code()}"
                    }
                } catch (e: Exception) {
                    respostaServidor = "Erro: ${e.message}"
                }
            }
        } else {
            // Modo Novo Registro
            val medicao = Medicao(
                valor = glicose.toIntOrNull() ?: 0,
                momento = momentoSelecionado,
                insulinas = listaInsulinas.toList(),
                carboidratos = carboidratos.toIntOrNull(),
                mealDescription = comidaDescricao.ifBlank { null },
                isTechnicalIncident = isErroTecnico,
                technicalNotes = if (isErroTecnico) "Incidente reportado pelo app" else null
            )

            viewModel.enviarMedicao(medicao) { sucesso, msg ->
                respostaServidor = msg
                if (sucesso) {
                    onSucesso()
                    onVoltar()
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onVoltar) { Icon(Icons.Default.ArrowBack, "Voltar") }
            Text(if (medicaoEdicao != null) "Editar Medição" else "Nova Medição", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth().weight(1f),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(4.dp)
        ) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {

                // Log de Erro Técnico
                Row(
                    modifier = Modifier.fillMaxWidth().background(Color(0xFFFFF5F5), RoundedCornerShape(8.dp)).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("LOG DE ERRO TÉCNICO", fontWeight = FontWeight.Bold, color = Color.Red, fontSize = 12.sp)
                        Text("Marcar como incidente ou falha", fontSize = 12.sp, color = Color.Gray)
                    }
                    Switch(checked = isErroTecnico, onCheckedChange = { isErroTecnico = it })
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Glicemia
                OutlinedTextField(
                    value = glicose, onValueChange = { glicose = it },
                    label = { Text("Glicemia (mg/dL)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)
                )

                // Banner de Sugestão de Insulina
                if (doseRecomendada.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFEBF8FF))) {
                        Text("💡 Sugestão baseada em suas metas: $doseRecomendada", modifier = Modifier.padding(16.dp), color = Color(0xFF2B6CB0), fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Carboidratos
                OutlinedTextField(
                    value = carboidratos, onValueChange = { carboidratos = it },
                    label = { Text("Carboidratos (g)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(8.dp))

                // IA Estimativa
                OutlinedTextField(
                    value = comidaDescricao, onValueChange = { comidaDescricao = it },
                    label = { Text("Estimativa por IA (Descreva o que comeu)") },
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                    trailingIcon = {
                        if (estimandoCarbs) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        } else if (comidaDescricao.isNotEmpty()) {
                            IconButton(onClick = {
                                estimandoCarbs = true
                                viewModel.estimarCarboidratos(comidaDescricao) { carbs, msg ->
                                    estimandoCarbs = false
                                    respostaServidor = msg
                                    if (carbs != null) {
                                        carboidratos = carbs.toString()
                                    }
                                }
                            }) {
                                Icon(Icons.Default.AutoAwesome, contentDescription = "Estimar")
                            }
                        }
                    }
                )
                if (comidaDescricao.isNotEmpty() && !estimandoCarbs) {
                    Text(
                        "Clique na varinha mágica para calcular",
                        fontSize = 11.sp,
                        color = Color.Gray,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Seção de Insulinas
                Text("Doses de Insulina", fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))

                // Lista de doses já adicionadas
                listaInsulinas.forEachIndexed { index, dose ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .background(Color(0xFFEBF8FF), RoundedCornerShape(8.dp))
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("💉 ${dose.dose} U - ${dose.tipo}", fontWeight = FontWeight.Bold, color = Color(0xFF2B6CB0))
                        IconButton(onClick = { listaInsulinas.removeAt(index) }) {
                            Icon(Icons.Default.Delete, "Remover", tint = Color.Red, modifier = Modifier.size(20.dp))
                        }
                    }
                }

                if (expandirInsulina) {
                    Card(
                        border = BorderStroke(1.dp, Color(0xFF3182CE)),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = doseTemp,
                                    onValueChange = { doseTemp = it },
                                    label = { Text("U") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.weight(1f)
                                )
                                
                                var tipoDropdownExpandido by remember { mutableStateOf(false) }
                                ExposedDropdownMenuBox(
                                    expanded = tipoDropdownExpandido,
                                    onExpandedChange = { tipoDropdownExpandido = it },
                                    modifier = Modifier.weight(2f)
                                ) {
                                    OutlinedTextField(
                                        value = tipoTemp,
                                        onValueChange = {},
                                        readOnly = true,
                                        label = { Text("Tipo") },
                                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = tipoDropdownExpandido) },
                                        modifier = Modifier.menuAnchor()
                                    )
                                    ExposedDropdownMenu(
                                        expanded = tipoDropdownExpandido,
                                        onDismissRequest = { tipoDropdownExpandido = false }
                                    ) {
                                        listOf("Rápida", "Basal").forEach { tipo ->
                                            DropdownMenuItem(
                                                text = { Text(tipo) },
                                                onClick = {
                                                    tipoTemp = tipo
                                                    tipoDropdownExpandido = false
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(onClick = { expandirInsulina = false }) { Text("Cancelar") }
                                Button(onClick = {
                                    val d = doseTemp.toDoubleOrNull()
                                    if (d != null) {
                                        listaInsulinas.add(DoseInsulina(d, tipoTemp))
                                        doseTemp = ""
                                        expandirInsulina = false
                                    }
                                }) { Text("Adicionar") }
                            }
                        }
                    }
                } else {
                    OutlinedButton(
                        onClick = { expandirInsulina = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Add, null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Adicionar Dose")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Momento da Medição (Dropdown)
                ExposedDropdownMenuBox(
                    expanded = dropdownExpandido,
                    onExpandedChange = { dropdownExpandido = !dropdownExpandido }
                ) {
                    OutlinedTextField(
                        value = momentoSelecionado,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Momento da Medição") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpandido) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                    ExposedDropdownMenu(
                        expanded = dropdownExpandido,
                        onDismissRequest = { dropdownExpandido = false }
                    ) {
                        momentos.forEach { selecao ->
                            DropdownMenuItem(
                                text = { Text(selecao) },
                                onClick = {
                                    momentoSelecionado = selecao
                                    dropdownExpandido = false
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Botão Salvar
                Button(
                    onClick = {
                        val valorGlicose = glicose.toIntOrNull() ?: 0
                        if (valorGlicose > 300) {
                            mostrarModalChecklist = true
                        } else {
                            executarEnvio()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3182CE))
                ) {
                    Text("Salvar Medição", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }

                if (mostrarModalChecklist) {
                    AlertDialog(
                        onDismissRequest = { mostrarModalChecklist = false },
                        title = { Text("Glicemia Alta! (> 300)") },
                        text = { 
                            Text("Verifique: bolhas na caneta, lipodistrofia no local e tempo de absorção.") 
                        },
                        confirmButton = {
                            Button(onClick = { 
                                mostrarModalChecklist = false
                                executarEnvio() 
                            }) { Text("Confirmar e Salvar") }
                        },
                        dismissButton = {
                            TextButton(onClick = { mostrarModalChecklist = false }) {
                                Text("Cancelar")
                            }
                        }
                    )
                }

                if (respostaServidor.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(respostaServidor, color = Color(0xFF3182CE), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun TelaAnaliseIA(viewModel: MainViewModel, onVoltar: () -> Unit) {
    var analiseMarkdown by remember { mutableStateOf("Gerando análise com Gemini...") }
    var carregando by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        viewModel.solicitarAnaliseIA { resultado ->
            analiseMarkdown = resultado
            carregando = false
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onVoltar) { Icon(Icons.Default.ArrowBack, "Voltar") }
            Text("Análise Profunda", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFF805AD5))
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (carregando) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color(0xFF805AD5))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("O Gemini está analisando suas tendências...", color = Color.Gray)
                }
            }
        } else {
            Card(
                modifier = Modifier.fillMaxWidth().weight(1f),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(4.dp)
            ) {
                Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                    // Nota: Para renderizar Markdown real, usaríamos uma lib externa, 
                    // mas aqui vamos formatar o texto básico ou usar Text normal
                    Text(
                        text = analiseMarkdown,
                        fontSize = 14.sp,
                        color = Color.DarkGray
                    )
                }
            }
        }
    }
}

fun processarDadosRecentes(context: Context, listaCompleta: List<MedicaoCompleta>) {
    // 1. Verifica Alertas Agudos (Notificação de Alerta)
    verificarAlertasAgudos(context, listaCompleta)

    // 2. Verifica Padrão de Jejum (Análise de Tendência)
    analisarPadraoJejum(context, listaCompleta)

    // 3. Atualiza gráfico (A lógica de UI já consome a listaCompleta em TelaPrincipal)
}

fun analisarPadraoJejum(context: Context, leituras: List<MedicaoCompleta>) {
    val leiturasJejum = leituras.filter { it.momento.contains("Jejum", ignoreCase = true) }.takeLast(3)
    
    if (leiturasJejum.size == 3) {
        val mediaJejum = leiturasJejum.map { it.valor }.average()
        if (mediaJejum > 130) {
            exibirNotificacao(
                context, 
                "Tendência: Glicemia de Jejum", 
                "Suas últimas manhãs estão acima de 130 mg/dL. Considere revisar sua ceia ou dose basal."
            )
        }
    }
}

fun verificarAlertasAgudos(context: Context, leituras: List<MedicaoCompleta>) {
    val prefs = context.getSharedPreferences("GlicoPrefs", 0)
    val targetMax = prefs.getInt("meta", 100) + 50 // Exemplo de teto de alerta
    val targetMin = 70 // Exemplo de piso de alerta

    if (leituras.size < 3) return

    val ultimas3 = leituras.takeLast(3)
    val todasAltas = ultimas3.all { it.valor > targetMax }
    val todasBaixas = ultimas3.all { it.valor < targetMin }

    if (todasAltas || todasBaixas) {
        val titulo = if (todasAltas) "Alerta: Glicose Alta" else "Alerta: Glicose Baixa"
        val mensagem = "Suas últimas 3 medições foram críticas. Consulte seu médico."

        exibirNotificacao(context, titulo, mensagem)
    }
}

fun exibirNotificacao(context: Context, titulo: String, mensagem: String) {
    val channelId = "alertas_glicemia"
    val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    // Configuração do canal para Android 8.0+
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
        val channel = NotificationChannel(channelId, "Alertas D.A.V.I.", NotificationManager.IMPORTANCE_HIGH)
        notificationManager.createNotificationChannel(channel)
    }

    val builder = NotificationCompat.Builder(context, channelId)
        .setSmallIcon(android.R.drawable.ic_dialog_alert)
        .setContentTitle(titulo)
        .setContentText(mensagem)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setAutoCancel(true)

    notificationManager.notify(1, builder.build())
}

fun verificarJejumAlto(leituras: List<MedicaoCompleta>): Boolean {
    // Filtra jejuns >= 200
    val jejunsAltos = leituras.filter {
        it.momento.contains("Jejum", true) && it.valor >= 200
    }

    if (jejunsAltos.size < 3) return false

    // Simplificando: verifica se as últimas 3 medições de jejum foram altas
    return true // Exemplo simplificado para manter o foco na UI
}
