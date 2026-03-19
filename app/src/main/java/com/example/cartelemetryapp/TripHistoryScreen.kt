package com.example.cartelemetryapp

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.saveable.rememberSaveable

@Composable
fun TripHistoryScreen(onBack: () -> Unit) {

    val trips = rememberSaveable { mutableStateOf<List<TripSummary>>(emptyList()) }
    val isLoading = remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {

        try {

            val result = withContext(Dispatchers.IO) {
                ServiceNowClient.fetchTripHistory()
            }

            trips.value = result

        } catch (e: Exception) {

            e.printStackTrace()

        } finally {

            isLoading.value = false
        }
    }

    Column {

        Button(
            onClick = onBack,
            modifier = Modifier.padding(8.dp)
        ) {
            Text("Back")
        }

        if (isLoading.value) {

            Text(
                "Loading trips...",
                modifier = Modifier.padding(16.dp)
            )

            return@Column
        }

        LazyColumn(
            modifier = Modifier.weight(1f)
        ) {

            items(trips.value) { trip ->

                Card(
                    modifier = Modifier
                        .padding(8.dp)
                        .fillMaxWidth()
                ) {

                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {

                        Text("Start Time: ${trip.startTime}")
                        Text("End Time: ${trip.endTime}")
                        Text("Distance: ${trip.distanceMiles} mi")
                        Text("Duration: ${trip.durationMinutes} min")
                        Text("Avg Speed: ${trip.avgSpeed} mph")
                        Text("Fuel Used: ${trip.fuelUsedPercent?.let { "$it%" } ?: "--"}")
                        Text("Driving Score: ${trip.aggressivenessScore?.let{100-it}?:"--"}")
                    }
                }
            }
        }
    }
}

