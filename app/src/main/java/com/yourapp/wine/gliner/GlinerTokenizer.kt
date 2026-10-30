package com.yourapp.wine.gliner

import org.json.JSONArray
import org.json.JSONObject

/**
 * Minimal, dependency-free reader for the Hugging Face `tokenizer.json` that ships with a GLiNER
 * export. Only what GLiNER actually needs is implemented: word-level tokenization of an already
 * split word list (`is_split_into_words=True`), plus the special-token ids used by the prompt
 * template (`ent_token` / `sep_token`).
 *
 * Supported model families: `Unigram` (SentencePiece, the DeBERTa-v3 tokenizer used by
 * gliner_small-v2.1), `BPE` (byte-level GPT-2 style) and `WordPiece`. Unknown JSON shapes fail
 * closed with the offending field name instead of silently producing wrong token ids.
 */
internal class GlinerTokenizer private constructor(
    val modelType:String,
    private val vocabulary:Map<String,Int>,
    private val scores:Map<String,Double>,
    private val merges:Map<String,Int>,
    private val byteEncoder:Map<Int,String>?,
    private val continuingPrefix:String?,
    private val metaspace:String?,
    private val prependMetaspace:Boolean,
    private val lowerCase:Boolean,
    val unknownId:Int,
    val paddingId:Int,
    private val addedTokens:Map<String,Int>,
) {
    val vocabularySize:Int get() = maxOf(vocabulary.size, addedTokens.size)

    /** Single-token lookup for prompt markers. Returns null when the piece is not in the vocabulary. */
    fun tokenId(piece:String):Int? = addedTokens[piece] ?: vocabulary[piece]

    fun describe():String = "$modelType · ${vocabulary.size} pieces · unk $unknownId · pad $paddingId"+
        (metaspace?.let { " · metaspace '$it'" } ?: "")+(if(lowerCase) " · lowercased" else "")

    /**
     * Token ids for one already-split prompt word. Prompt markers (`<<ENT>>`, `<<SEP>>`) are used as
     * plain text by the model: the gliner library feeds their SentencePiece pieces, not the added
     * token id, so this deliberately skips the added-token shortcut.
     */
    fun encodePromptWord(word:String):List<Int> = encodePieces(word,false)

    /** Token ids for one already-split word. Never returns an empty array. */
    fun encodeWord(word:String):List<Int> = encodePieces(word,true)

    private fun encodePieces(word:String,allowAddedTokens:Boolean):List<Int> {
        if(word.isEmpty()) return listOf(unknownId)
        if(allowAddedTokens) addedTokens[word]?.let { return listOf(it) }
        val prepared=if(lowerCase) word.lowercase(java.util.Locale.ROOT) else word
        val ids=when(modelType) {
            "Unigram" -> unigram(prepared)
            "BPE" -> bpe(prepared)
            "WordPiece" -> wordPiece(prepared)
            else -> error("Unsupported tokenizer model type '$modelType'")
        }
        return if(ids.isEmpty()) listOf(unknownId) else ids
    }

    private fun unigram(word:String):List<Int> {
        val meta=metaspace ?: ""
        val surface=if(meta.isEmpty()) word else meta+word.replace(" ",meta)
        val chars=surface.length
        val best=DoubleArray(chars+1) { Double.NEGATIVE_INFINITY }
        val back=IntArray(chars+1) { -1 }
        val pieceAt=arrayOfNulls<String>(chars+1)
        best[0]=0.0
        for(end in 1..chars) {
            val shortest=maxOf(1,end-64)
            for(start in shortest-1 downTo 0) {
                if(best[start]==Double.NEGATIVE_INFINITY) continue
                val piece=surface.substring(start,end)
                val score=scores[piece] ?: continue
                val candidate=best[start]+score
                if(candidate>best[end]) { best[end]=candidate;back[end]=start;pieceAt[end]=piece }
            }
        }
        if(back[chars]<0) return listOf(unknownId)
        val ids=mutableListOf<Int>()
        var cursor=chars
        while(cursor>0) {
            val start=back[cursor]
            val piece=pieceAt[cursor] ?: return listOf(unknownId)
            val id=vocabulary[piece] ?: return listOf(unknownId)
            ids.add(0,id)
            cursor=start
        }
        return ids
    }

    private fun bpe(word:String):List<Int> {
        val symbols=mutableListOf<String>()
        val surface=if(prependMetaspace && metaspace!=null) metaspace+word else word
        val encoder=byteEncoder
        if(encoder!=null) {
            surface.toByteArray(Charsets.UTF_8).forEach { byte ->
                symbols += encoder[byte.toInt() and 0xFF] ?: return listOf(unknownId)
            }
        } else {
            surface.forEach { symbols += it.toString() }
        }
        var guard=0
        while(symbols.size>1 && guard++<512) {
            var bestRank=Int.MAX_VALUE
            var bestIndex=-1
            for(index in 0 until symbols.size-1) {
                val rank=merges["${symbols[index]} ${symbols[index+1]}"] ?: continue
                if(rank<bestRank) { bestRank=rank;bestIndex=index }
            }
            if(bestIndex<0) break
            val merged=symbols[bestIndex]+symbols[bestIndex+1]
            symbols.removeAt(bestIndex+1)
            symbols[bestIndex]=merged
        }
        val ids=symbols.map { vocabulary[it] ?: return listOf(unknownId) }
        return ids
    }

    private fun wordPiece(word:String):List<Int> {
        val ids=mutableListOf<Int>()
        var start=0
        val limit=word.length
        while(start<limit) {
            var end=limit
            var matched=-1
            while(end>start) {
                var piece=word.substring(start,end)
                if(start>0) piece=(continuingPrefix ?: "##")+piece
                val id=vocabulary[piece]
                if(id!=null) { matched=id;break }
                end--
            }
            if(matched<0) return listOf(unknownId)
            ids += matched
            start=end
            if(ids.size>128) return listOf(unknownId)
        }
        return ids
    }

    companion object {
        fun parse(text:String):GlinerTokenizer {
            val root=JSONObject(text)
            val model=root.optJSONObject("model") ?: error("tokenizer.json has no model section")
            val modelType=model.optString("type")
            val vocabulary=linkedMapOf<String,Int>()
            val scores=linkedMapOf<String,Double>()
            when(modelType) {
                "Unigram" -> {
                    val vocab=model.optJSONArray("vocab") ?: error("Unigram tokenizer has no vocab")
                    for(index in 0 until vocab.length()) {
                        val entry=vocab.getJSONArray(index)
                        val piece=entry.getString(0)
                        vocabulary[piece]=index
                        scores[piece]=if(entry.length()>1) entry.getDouble(1) else 0.0
                    }
                }
                "BPE" -> {
                    val vocab=model.optJSONObject("vocab") ?: error("BPE tokenizer has no vocab")
                    vocab.keys().forEach { piece -> vocabulary[piece]=vocab.getInt(piece) }
                }
                "WordPiece" -> {
                    val vocab=model.optJSONObject("vocab") ?: error("WordPiece tokenizer has no vocab")
                    vocab.keys().forEach { piece -> vocabulary[piece]=vocab.getInt(piece) }
                }
                else -> error("Unsupported tokenizer model type '$modelType'")
            }
            val merges=linkedMapOf<String,Int>()
            model.optJSONArray("merges")?.let { entries ->
                for(index in 0 until entries.length()) {
                    val rank=index
                    val element=entries.opt(index)
                    val key=when(element) {
                        is String -> element
                        is JSONArray -> "${element.getString(0)} ${element.getString(1)}"
                        else -> null
                    } ?: continue
                    merges.putIfAbsent(key,rank)
                }
            }
            val added=linkedMapOf<String,Int>()
            root.optJSONArray("added_tokens")?.let { entries ->
                for(index in 0 until entries.length()) {
                    val entry=entries.optJSONObject(index) ?: continue
                    val content=entry.optString("content")
                    if(content.isNotEmpty()) added[content]=entry.optInt("id",vocabulary[content] ?: -1)
                    if(content.isNotEmpty() && !vocabulary.containsKey(content)) {
                        val id=entry.optInt("id",-1)
                        if(id>=0) vocabulary[content]=id
                    }
                }
            }
            val lowerCase=containsType(root.opt("normalizer"),"Lowercase") ||
                containsType(root.opt("pre_tokenizer"),"Lowercase")
            val preTokenizer=root.opt("pre_tokenizer")
            val metaspace=metaspaceToken(preTokenizer)
            val prepend=prependScheme(preTokenizer)
            val byteEncoder=if(modelType=="BPE" && containsType(preTokenizer,"ByteLevel")) gpt2ByteEncoder() else null
            val continuing=model.optString("continuing_subword_prefix").takeIf { it.isNotEmpty() }
            fun idOf(vararg pieces:String):Int? = pieces.firstNotNullOfOrNull { piece -> added[piece] ?: vocabulary[piece] }
            val unknown=model.optInt("unk_id",-1).takeIf { it>=0 }
                ?: idOf("[UNK]","<unk>","[unk]")
                ?: 0
            val padding=idOf("[PAD]","<pad>","[pad]") ?: unknown
            return GlinerTokenizer(modelType,vocabulary,scores,merges,byteEncoder,continuing,
                metaspace,prepend,lowerCase,unknown,padding,added)
        }

        /** Depth-first scan for a tokenizer normalizer/pre-tokenizer type name. */
        private fun containsType(node:Any?,type:String):Boolean = when(node) {
            null -> false
            is JSONObject -> {
                if(node.optString("type")==type) true
                else node.keys().asSequence().any { containsType(node.opt(it),type) }
            }
            is JSONArray -> (0 until node.length()).any { containsType(node.opt(it),type) }
            else -> false
        }

        private fun metaspaceToken(node:Any?):String? = when(node) {
            null -> null
            is JSONObject -> if(node.optString("type")=="Metaspace") {
                node.optString("replacement").ifEmpty { "▁" }
            } else node.keys().asSequence().firstNotNullOfOrNull { metaspaceToken(node.opt(it)) }
            is JSONArray -> (0 until node.length()).firstNotNullOfOrNull { metaspaceToken(node.opt(it)) }
            else -> null
        }

        private fun prependScheme(node:Any?):Boolean = when(node) {
            null -> true
            is JSONObject -> if(node.optString("type")=="Metaspace") {
                when(node.optString("prepend_scheme","always")) {
                    "never" -> false
                    else -> true
                }
            } else node.keys().asSequence().map { prependScheme(node.opt(it)) }.firstOrNull { it } ?: true
            is JSONArray -> (0 until node.length()).map { prependScheme(node.opt(it)) }.firstOrNull { it } ?: true
            else -> true
        }

        /** GPT-2 byte<->unicode table, needed only for a byte-level BPE export. */
        private fun gpt2ByteEncoder():Map<Int,String> {
            val direct=(33..126).toMutableList().apply { addAll(161..172);addAll(174..255) }
            val mapped=direct.toMutableSet()
            val result=linkedMapOf<Int,String>()
            var extra=0
            for(byte in 0..255) {
                if(byte in mapped) result[byte]=byte.toChar().toString()
                else { result[byte]=(256+extra).toChar().toString();extra++ }
            }
            return result
        }
    }
}

/** Parsed `gliner_config.json`: the prompt markers and span settings the export was trained with. */
internal data class GlinerConfig(
    val entToken:String,
    val sepToken:String,
    val maxWidth:Int,
    val maxLen:Int,
    val classTokenIndex:Int,
    val architecture:String,
    val raw:JSONObject,
) {
    companion object {
        fun parse(text:String):GlinerConfig {
            val json=JSONObject(text)
            return GlinerConfig(
                entToken=json.optString("ent_token").ifEmpty { "<<" },
                sepToken=json.optString("sep_token").ifEmpty { ">>" },
                maxWidth=json.optInt("max_width",12).coerceIn(1,32),
                maxLen=json.optInt("max_len",384).coerceIn(32,1024),
                classTokenIndex=json.optInt("class_token_index",-1),
                architecture=json.optString("model_type").ifEmpty { json.optString("architecture","gliner") },
                raw=json,
            )
        }
    }
}
