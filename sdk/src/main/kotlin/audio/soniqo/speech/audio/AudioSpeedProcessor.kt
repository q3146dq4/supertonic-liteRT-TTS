package audio.soniqo.speech.audio

import sonic.Sonic
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Pitch-preserving speech-rate adjustment using the Android Sonic implementation. */
object AudioSpeedProcessor {
    data class Result(val pcm16: ByteArray, val processingMs: Double)

    fun apply(pcm16: ByteArray, sampleRate: Int, speed: Float): Result {
        val start = System.nanoTime()
        val clamped = speed.coerceIn(0.25f, 3.0f)
        if (pcm16.isEmpty() || kotlin.math.abs(clamped - 1.0f) < 0.001f) {
            return Result(pcm16, (System.nanoTime() - start) / 1_000_000.0)
        }
        require(pcm16.size % 2 == 0) { "PCM16 must contain complete samples" }
        val inputSamples = pcm16.size / 2
        val input = ShortArray(inputSamples)
        ByteBuffer.wrap(pcm16).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(input)

        val sonic = Sonic(sampleRate, 1)
        sonic.setSpeed(clamped)
        sonic.setPitch(1.0f)
        sonic.setRate(1.0f)
        sonic.setVolume(1.0f)
        sonic.setChordPitch(false)
        sonic.setQuality(0)
        sonic.writeShortToStream(input, input.size)
        sonic.flushStream()

        val chunks = ArrayList<ShortArray>()
        var totalRead = 0
        while (true) {
            val available = sonic.samplesAvailable()
            if (available <= 0) break
            val buffer = ShortArray(available)
            val read = sonic.readShortFromStream(buffer, available)
            if (read <= 0) break
            if (read == buffer.size) {
                chunks.add(buffer)
            } else {
                chunks.add(buffer.copyOf(read))
            }
            totalRead += read
        }
        val bytes = ByteArray(totalRead * 2)
        val out = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        for (chunk in chunks) out.put(chunk)
        return Result(bytes, (System.nanoTime() - start) / 1_000_000.0)
    }
}
