package com.mapbox.navigation.examples.history

import com.google.gson.annotations.SerializedName
import com.mapbox.navigation.core.replay.history.ReplayHistoryDTO
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import timber.log.Timber

data class ReplayPath(
    @SerializedName("title")
    val title: String,
    @SerializedName("description")
    val description: String,
    @SerializedName("path")
    val path: String
)

class HistoryFilesClient {

    companion object {
        private const val BASE_URL = "https://mapbox.github.io/mapbox-navigation-history/"
        private val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    interface LocalhostFiles {
        @GET("index.json")
        fun drives(): Call<List<ReplayPath>>

        @GET("navigation-history/{filename}")
        fun jsonFile(@Path("filename") filename: String): Call<ReplayHistoryDTO>
    }

    suspend fun requestHistory(): List<ReplayPath> =
        withContext(Dispatchers.IO) { requestHistoryCall() }

    private suspend fun requestHistoryCall(): List<ReplayPath> = suspendCoroutine { cont ->
        val historyDrives = retrofit.create(LocalhostFiles::class.java)

        historyDrives.drives().enqueue(object : Callback<List<ReplayPath>> {
            override fun onFailure(call: Call<List<ReplayPath>>, t: Throwable) {
                Timber.e(t, "requestHistory onFailure")
                cont.resume(emptyList())
            }

            override fun onResponse(call: Call<List<ReplayPath>>, response: Response<List<ReplayPath>>) {
                Timber.i("requestHistory onResponse")
                val drives = if (response.isSuccessful) {
                    response.body() ?: emptyList()
                } else {
                    emptyList()
                }
                cont.resume(drives)
            }
        })
    }

    suspend fun requestJsonFile(filename: String): ReplayHistoryDTO? =
        withContext(Dispatchers.IO) { requestJsonFileCall(filename) }

    private suspend fun requestJsonFileCall(filename: String): ReplayHistoryDTO? = suspendCoroutine { cont ->
        val historyDrives = retrofit.create(LocalhostFiles::class.java)

        historyDrives.jsonFile(filename).enqueue(object : Callback<ReplayHistoryDTO> {
            override fun onFailure(call: Call<ReplayHistoryDTO>, t: Throwable) {
                Timber.e(t, "requestData onFailure")
                cont.resume(null)
            }

            override fun onResponse(call: Call<ReplayHistoryDTO>, response: Response<ReplayHistoryDTO>) {
                Timber.i("requestData onResponse")
                val data = if (response.isSuccessful) {
                    response.body()
                } else {
                    null
                }
                cont.resume(data)
            }
        })
    }
}