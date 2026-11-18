package com.jarvis.smarthomelauncher.widgets.commute.Repository

import com.jarvis.smarthomelauncher.widgets.commute.Models.CommuteRoute
import com.jarvis.smarthomelauncher.widgets.commute.Models.TransportProvider
import com.jarvis.smarthomelauncher.widgets.commute.Services.CommuteServiceProvider
import com.jarvis.smarthomelauncher.widgets.commute.Services.TflService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class TflProvider : CommuteServiceProvider {
    override val providerType = TransportProvider.TFL

    private val service: TflService = Retrofit.Builder()
        .baseUrl("https://api.tfl.gov.uk/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(TflService::class.java)

    override suspend fun getRoutes(): List<CommuteRoute> = withContext(Dispatchers.IO) {
        try {
            val response = service.getLineStatus("victoria,jubilee,northern")
            if (response.isSuccessful) {
                response.body()?.map { line ->
                    CommuteRoute(
                        id = line.id,
                        provider = providerType,
                        name = line.name,
                        status = line.lineStatuses.firstOrNull()?.statusSeverityDescription ?: "Unknown",
                        estimatedArrival = null,
                        durationMins = null
                    )
                } ?: emptyList()
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}

class CommuteRepository {
    private val providers = listOf(TflProvider()) // We can add more providers here later

    suspend fun getAllRoutes(): List<CommuteRoute> {
        val allRoutes = mutableListOf<CommuteRoute>()
        for (provider in providers) {
            allRoutes.addAll(provider.getRoutes())
        }
        return allRoutes
    }
}
