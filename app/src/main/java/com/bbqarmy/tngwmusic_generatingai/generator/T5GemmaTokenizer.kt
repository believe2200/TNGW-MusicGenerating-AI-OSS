package com.bbqarmy.tngwmusic_generatingai.generator

import org.json.JSONObject
import java.io.File

class T5GemmaTokenizer {
    val vocab = mutableMapOf<String, Int>()
    var bosTokenId: Int = 1
    var eosTokenId: Int = 2
    var padTokenId: Int = 0
    private val maxSequenceLength = 256

    fun isLoaded(): Boolean = vocab.isNotEmpty()

    fun load(tokenizerFile: File) {
        val jsonString = tokenizerFile.readText()
        val json = JSONObject(jsonString)
        
        // 語彙の読み込み (model.vocab 内にあることが多い)
        val model = json.getJSONObject("model")
        val vocabJson = model.getJSONObject("vocab")
        
        vocabJson.keys().forEach { key ->
            vocab[key] = vocabJson.getInt(key)
        }

        // 特殊トークンの取得 (オプション)
        if (json.has("added_tokens")) {
            val addedTokens = json.getJSONArray("added_tokens")
            for (i in 0 until addedTokens.length()) {
                val obj = addedTokens.getJSONObject(i)
                val content = obj.getString("content")
                val id = obj.getInt("id")
                if (content == "<bos>") bosTokenId = id
                if (content == "</s>") eosTokenId = id
                if (content == "<pad>") padTokenId = id
            }
        }
    }

    /**
     * テキストをトークンIDの配列（LongArray）に変換します。
     * 常に 256 トークンになるようにパディング/切り詰めを行います。
     */
    fun encode(text: String): LongArray {
        val tokens = mutableListOf<Long>()
        
        // T5 usually doesn't use BOS. Gemma does. 
        // For Stable Audio (T5-based), we should skip BOS unless specifically needed.
        // tokens.add(bosTokenId.toLong()) 
        
        if (text.isEmpty()) {
            val result = LongArray(maxSequenceLength) { padTokenId.toLong() }
            if (maxSequenceLength > 0) result[0] = eosTokenId.toLong()
            return result
        }

        // T5/Gemma uses U+2581 (lower one quarter block) instead of space
        val t5Space = "\u2581"

        // Most T5 models expect a leading space for the first word.
        // We also remove lowercase() to allow the model to see natural casing if it supports it.
        val processedText = t5Space + text.replace(" ", t5Space)
        
        var remaining = processedText
        while (remaining.isNotEmpty()) {
            var found = false
            for (len in remaining.length downTo 1) {
                val sub = remaining.substring(0, len)
                if (vocab.containsKey(sub)) {
                    tokens.add(vocab[sub]!!.toLong())
                    remaining = remaining.substring(len)
                    found = true
                    break
                }
            }
            if (!found) {
                // Unknown character: skip or map to UNK (usually token ID 3)
                remaining = remaining.substring(1)
            }
        }

        // Add EOS token at the end
        if (tokens.size < maxSequenceLength) {
            tokens.add(eosTokenId.toLong())
        }

        // Adjust to fixed length
        val result = LongArray(maxSequenceLength) { padTokenId.toLong() }
        for (i in 0 until minOf(tokens.size, maxSequenceLength)) {
            result[i] = tokens[i]
        }
        
        return result
    }
}
