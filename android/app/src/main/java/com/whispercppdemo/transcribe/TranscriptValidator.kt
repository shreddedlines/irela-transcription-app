package com.whispercppdemo.transcribe

/**
 * Whether final ASR output is worth saving as a transcript.
 *
 * `isBlank()` was not enough: on a real device a very short clip came back as
 * "." and was stored as a successful one-word transcript. The rule here is
 * deliberately small and language-neutral -- the text must contain at least
 * one letter or digit in ANY script. Devanagari consonants and vowels are
 * letters, so Hindi passes; so do Latin, digits, and mixed text.
 *
 * It does NOT try to judge quality, filter filler words, or recognise
 * hallucination markers. Anything that aggressive would start rejecting real
 * speech in some language, which is a worse failure than keeping a poor
 * transcript.
 */
object TranscriptValidator {

    fun isMeaningful(text: String?): Boolean {
        if (text == null) return false
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            // Code points, not chars, so supplementary-plane scripts count.
            if (Character.isLetterOrDigit(cp)) return true
            i += Character.charCount(cp)
        }
        return false
    }
}
