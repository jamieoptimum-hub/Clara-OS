package com.jarvis.smarthomelauncher.widgets.commute.Services

import com.jarvis.smarthomelauncher.widgets.commute.Models.CommuteRoute
import com.jarvis.smarthomelauncher.widgets.commute.Models.TransportProvider

interface CommuteServiceProvider {
    val providerType: TransportProvider
    suspend fun getRoutes(): List<CommuteRoute>
}
