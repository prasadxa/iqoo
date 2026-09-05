package com.aasra.cloud

import org.junit.Assert.*
import org.junit.Test

class SpeechInputTest {
    @Test fun preservesTextMathAndPronunciation() {
        for (text in listOf(
            "दवा नहीं लें। naa-hee, डॉ. शर्मा।", "2 < 3 and 5 > 4; a<b; x_y = 2 * 3 * 4; -5 + 2",
            "AT&T, https://example.org/a_b, C:\\notes", "Do not stop taking it. Never double it!",
            "x<y && y>0; x < limit + 2; dose <= 5; 2**3 = 8",
        )) assertEquals(text, SpeechInput.normalize(text))
    }

    @Test fun removesMarkupNotItsWordsOrNegation() {
        assertEquals("Do not take it.", SpeechInput.normalize("<speak>Do <vendor:emotion name='happy'><emphasis>not</emphasis></vendor:emotion> take it.</speak>"))
        assertEquals("naa-hee", SpeechInput.normalize("<phoneme ph='wrong'>naa-hee</phoneme>"))
        assertEquals("do not take", SpeechInput.normalize("<sub alias='take'>do not take</sub>"))
        assertEquals("not", SpeechInput.normalize("<audio src='https://example.org'>not</audio>"))
        assertEquals("नहीं", SpeechInput.normalize("<भाव स्तर='धीरे'>नहीं</भाव>"))
        assertEquals("Hello, friend. Ask first.", SpeechInput.normalize("<speak><s>Hello<break time='9s'/> friend.</s><p>Ask first.</p></speak>"))
    }

    @Test fun stripsOnlyFormattingAndKeepsLinkLabels() {
        assertEquals("Do not take this. Ask your doctor.", SpeechInput.normalize("## Do **not** take `this`.\n[Ask your doctor](https://example.org)."))
        assertEquals("not never", SpeechInput.normalize("__not__ ~~never~~"))
        assertEquals("Do not take it", SpeechInput.normalize("```text\nDo not take it\n```"))
        assertEquals("Take it not", SpeechInput.normalize("Take it\n```not\n```"))
        assertEquals("Take it not", SpeechInput.normalize("Take it\n``` not\n```"))
    }

    @Test fun decodesOnlySafeTextEntities() {
        assertEquals("\"don't\" & 2 < 3 > 1, नहीं", SpeechInput.normalize("&quot;don&apos;t&quot; &amp; 2 &lt; 3 &gt; 1, &#x928;&#x939;&#x940;&#x902;"))
        assertEquals("A & B", SpeechInput.normalize("<speak>A & B</speak>"))
    }

    @Test fun emptyAndBoundaryLengthAreExplicit() {
        assertEquals("", SpeechInput.normalize(" \n "))
        assertEquals("", SpeechInput.normalize("<speak><break/></speak>"))
        assertEquals("a".repeat(4096), SpeechInput.normalize("a".repeat(4096)))
        rejects("a".repeat(4097))
    }

    @Test fun rejectsDeclarationsMalformedMarkupAndEncodedTagsWithoutPartialSpeech() {
        for (text in listOf(
            "<!DOCTYPE speak [<!ENTITY x SYSTEM 'https://example.org'>]><speak>&x;</speak>",
            "<!DOCTYPE speak [<!ENTITY x 'not'>]><speak>&x;</speak>",
            "<?xml version='1.0'?><speak>not</speak>", "<![CDATA[not]]>",
            "<speak>take<emphasis>not</speak>", "<prosody rate='slow'", "<vendor option='unfinished",
            "&lt;emotion value='angry'/&gt;Do not take it.", "<speak>&unknown;</speak>",
            "<vendor broken>do not</vendor>", "<vendor option=fast/>", "<vendor broken",
            "<भाव स्तर=धीरे>नहीं</भाव>",
            "&lt;!DOCTYPE speak&gt;not", "&lt;?xml?&gt;not", "<x/>".repeat(513),
            "<speak>".repeat(33) + "not" + "</speak>".repeat(33), "a\u0000b",
        )) rejects(text)
    }

    private fun rejects(text: String) {
        assertTrue("Expected whole input rejection", runCatching { SpeechInput.normalize(text) }.exceptionOrNull() is IllegalArgumentException)
    }
}
