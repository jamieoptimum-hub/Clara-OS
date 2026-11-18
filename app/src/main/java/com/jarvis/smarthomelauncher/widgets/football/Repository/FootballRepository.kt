package com.jarvis.smarthomelauncher.widgets.football.Repository

import com.jarvis.smarthomelauncher.widgets.football.Services.ApiFootballService
import com.jarvis.smarthomelauncher.widgets.football.Models.ApiFootballFixture
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FootballRepository(private val apiKey: String) {
    private val service: ApiFootballService = Retrofit.Builder()
        .baseUrl("https://v3.football.api-sports.io/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(ApiFootballService::class.java)

    suspend fun getFixturesForToday(leagueId: Int? = 39, season: Int? = 2023): List<ApiFootballFixture> = withContext(Dispatchers.IO) {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        try {
            val response = service.getFixtures(apiKey, today, leagueId, season)
            if (response.isSuccessful) {
                response.body()?.response ?: emptyList()
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
