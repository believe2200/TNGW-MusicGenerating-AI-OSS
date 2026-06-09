package com.bbqarmy.tngwmusic_generatingai.util

import android.content.Context
import com.bbqarmy.tngwmusic_generatingai.ui.GeneratedTrack
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class TrackHistoryManager(context: Context) {
    private val historyFile = File(context.filesDir, "track_history.json")

    fun saveHistory(tracks: List<GeneratedTrack>) {
        val jsonArray = JSONArray()
        tracks.forEach { track ->
            val jsonObject = JSONObject()
            jsonObject.put("filePath", track.file.absolutePath)
            jsonObject.put("prompt", track.prompt)
            jsonObject.put("seed", track.seed)
            jsonObject.put("durationMs", track.durationMs)
            jsonArray.put(jsonObject)
        }
        historyFile.writeText(jsonArray.toString())
    }

    fun loadHistory(): List<GeneratedTrack> {
        if (!historyFile.exists()) return emptyList()
        
        return try {
            val jsonString = historyFile.readText()
            val jsonArray = JSONArray(jsonString)
            val tracks = mutableListOf<GeneratedTrack>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val file = File(obj.getString("filePath"))
                if (file.exists()) {
                    tracks.add(
                        GeneratedTrack(
                            file = file,
                            prompt = obj.getString("prompt"),
                            seed = obj.getLong("seed"),
                            durationMs = obj.optLong("durationMs", 0L)
                        )
                    )
                }
            }
            tracks
        } catch (_: Exception) {
            emptyList()
        }
    }
}
