package dev.murmur.speech

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Reads 16-bit PCM WAV files into float samples. Only used for tests and benchmarks. */
object WavReader {
    data class Wav(val samples: FloatArray, val sampleRate: Int)

    fun read(file: File): Wav {
        val buf = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        require(buf.remaining() >= 12 && String(buf.array(), 0, 4) == "RIFF" && String(buf.array(), 8, 4) == "WAVE") {
            "Not a WAV file: $file"
        }
        buf.position(12)
        var channels = 1
        var sampleRate = 16_000
        var bits = 16
        while (buf.remaining() >= 8) {
            val id = String(buf.array(), buf.position(), 4)
            buf.position(buf.position() + 4)
            val size = buf.int
            val body = buf.position()
            when (id) {
                "fmt " -> {
                    require(buf.short.toInt() == 1) { "Only PCM WAV is supported: $file" }
                    channels = buf.short.toInt()
                    sampleRate = buf.int
                    buf.position(body + 14)
                    bits = buf.short.toInt()
                }
                "data" -> {
                    require(bits == 16) { "Only 16-bit WAV is supported: $file" }
                    val frames = minOf(size, buf.remaining()) / 2 / channels
                    val out = FloatArray(frames)
                    for (i in 0 until frames) {
                        var sum = 0
                        repeat(channels) { sum += buf.short }
                        out[i] = sum / channels / 32768f
                    }
                    return Wav(out, sampleRate)
                }
            }
            buf.position(body + size + (size and 1))
        }
        error("No audio data in $file")
    }
}
