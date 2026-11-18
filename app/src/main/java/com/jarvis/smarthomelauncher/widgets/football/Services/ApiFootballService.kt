package com.jarvis.smarthomelauncher.widgets.football.Services

import com.jarvis.smarthomelauncher.widgets.football.Models.ApiFootballResponse
import com.jarvis.smarthomelauncher.widgets.football.Models.ApiFootballFixture
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query

interface ApiFootballService {
    @GET("fixtures")
    suspend fun getFixtures(
        @Header("x-apisports-key") apiKey: String,
        @Query("date") date: String,
        @Query("league") leagueId: Int? = null,
        @Query("season") season: Int? = null
    ): Response<ApiFootballResponse<ApiFootballFixture>>
}
