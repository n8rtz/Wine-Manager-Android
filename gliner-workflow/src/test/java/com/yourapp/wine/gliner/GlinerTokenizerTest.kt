package com.yourapp.wine.gliner

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The device reported `prompt ids: 128002,3,128002,3,128002,3,128003` with `unknown tokens: 15`:
 * every label word was <unk>, because the Unigram search only considered pieces starting at
 * position 0. This pins the tokenizer against a vocabulary where a word must be split into two
 * pieces, which is the case that used to collapse.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class GlinerTokenizerTest {
    private fun tokenizer():GlinerTokenizer {
        val pieces=listOf(
            "<unk>","<s>","</s>","[PAD]","[CLS]","[SEP]",
            "▁wine","▁producer","▁or","▁winery","▁name","▁appellation","▁region","▁country",
            "▁pr","od","uc","er",
        )
        val vocab=pieces.mapIndexed { index,piece -> "[\"$piece\",0.0]" }.joinToString(",")
        val json="""{
          "normalizer":null,
          "pre_tokenizer":{"type":"Metaspace","replacement":"\u2581","prepend_scheme":"always","split":true},
          "model":{"type":"Unigram","unk_id":0,"vocab":[$vocab]},
          "added_tokens":[
            {"id":128002,"content":"<<ENT>>","special":true},
            {"id":128003,"content":"<<SEP>>","special":true}
          ]
        }"""
        return GlinerTokenizer.parse(json)
    }

    @Test fun prompt_markers_are_the_added_token_ids() {
        val tokenizer=tokenizer()
        assertEquals(128002,tokenizer.tokenId("<<ENT>>"))
        assertEquals(128003,tokenizer.tokenId("<<SEP>>"))
    }

    /** The regression: "producer" is not one piece, so it must come back as several ids. */
    @Test fun a_word_that_needs_several_pieces_is_still_tokenized() {
        val tokenizer=tokenizer()
        val ids=tokenizer.encodeWord("producer")
        assertTrue("expected a multi-piece encoding, got $ids",ids.size>1)
        assertTrue("no id may be <unk> ($ids)",ids.none { it==tokenizer.unknownId })
    }

    @Test fun a_label_phrase_is_one_id_per_word() {
        val tokenizer=tokenizer()
        val ids=tokenizer.encodeWord("wine producer or winery name")
        assertEquals(5,ids.size)
        assertTrue(ids.none { it==tokenizer.unknownId })
    }

    @Test fun text_outside_the_vocabulary_falls_back_to_unk_without_throwing() {
        val tokenizer=tokenizer()
        assertEquals(listOf(tokenizer.unknownId),tokenizer.encodeWord("zzzzzz"))
    }
}
