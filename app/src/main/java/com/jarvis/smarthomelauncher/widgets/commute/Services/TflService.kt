package com.jarvis.smarthomelauncher.widgets.commute.Services

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path

// TfL API Models
data class TflLineStatus(val id: String, val name: String, val lineStatuses: List<Status>)
data class Status(val statusSeverityDescription: String)

interface TflService {
    @GET("Line/{lines}/Status")
    suspend fun getLineStatus(
        @Path("lines") lines: String
    ): Response<List<TflLineStatus>>
}
