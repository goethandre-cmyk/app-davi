package com.example.glicocontroldavi

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST

data class DoseInsulina(
    val dose: Double,
    val tipo: String // "Rápida" ou "Basal"
)

// 1. Data classes para enviar dados
data class Medicao(
    val valor: Int,
    val momento: String,
    val insulinas: List<DoseInsulina> = emptyList(),
    val carboidratos: Int? = null,
    val mealDescription: String? = null,
    val isTechnicalIncident: Boolean = false,
    val isAIRecord: Boolean = false,
    val technicalNotes: String? = null
)

// 2. Data classes para receber a resposta do registro
data class RespostaServidor(
    val status: String? = null,
    val IA_resposta: String?,
    val detalhe: String? = null
)

data class RespostaAnaliseIA(
    val status: String,
    val analiseMarkdown: String
)

data class RequisicaoEstimativa(
    val descricao: String
)

data class RespostaEstimativa(
    val status: String,
    val carboidratos: Int,
    val justificativa: String? = null
)

// 3. Data classes para o Histórico
data class RespostaHistorico(
    val status: String,
    @SerializedName("medicoes")
    val historico: List<MedicaoCompleta>
)

data class MedicaoCompleta(
    val id: String? = null,
    val valor: Int,
    val data: String? = "Sem data",
    val momento: String,
    val carboidratos: Int? = null,
    val isTechnicalIncident: Boolean = false,
    val insulinas: List<DoseInsulina>? = emptyList(),
    val mealDescription: String? = null,
    val isAIRecord: Boolean = false
) {
    // Helpers para compatibilidade com código antigo (opcional)
    val doseInsulina: Double? get() = insulinas?.firstOrNull()?.dose
    val tipoInsulina: String? get() = insulinas?.firstOrNull()?.tipo
}

// 4. Interface do Retrofit
interface ApiService {
    @POST("registrar")
    suspend fun enviarMedicao(
        @Header("Authorization") token: String,
        @Body medicao: Medicao
    ): Response<RespostaServidor>

    @GET("historico")
    suspend fun obterHistorico(
        @Header("Authorization") token: String
    ): Response<RespostaHistorico>

    @POST("analisar")
    suspend fun analisarPadroes(
        @Header("Authorization") token: String,
        @Body medicoes: List<MedicaoCompleta>
    ): Response<RespostaAnaliseIA>

    @POST("estimar-carboidratos")
    suspend fun estimarCarboidratos(
        @Header("Authorization") token: String,
        @Body requisicao: RequisicaoEstimativa
    ): Response<RespostaEstimativa>

    @POST("atualizar-medicao")
    suspend fun atualizarMedicao(
        @Header("Authorization") token: String,
        @Body medicao: MedicaoCompleta
    ): Response<RespostaServidor>

    @POST("deletar-medicao")
    suspend fun deletarMedicao(
        @Header("Authorization") token: String,
        @Body requisicao: Map<String, String> // Passando {"id": "..."}
    ): Response<RespostaServidor>
}
