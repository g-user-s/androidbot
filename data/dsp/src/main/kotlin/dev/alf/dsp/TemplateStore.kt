package dev.alf.dsp

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Reads and writes the reference templates the matcher compares against.
 *
 * Templates are produced once, off the hot path, by synthesising every phrase in the offline
 * vocabulary with the Turkish voices installed on the device; this is the file that survives
 * between runs so that work is not repeated at every boot. Coefficients go out as floats: the
 * matcher compares distances rather than reproducing the audio, and halving the file also halves
 * the parsing this weak CPU does at startup.
 */
object TemplateStore {

    private const val MAGIC = 0x414C4631 // "ALF1"
    private const val VERSION = 1

    fun write(templates: List<PhraseTemplate>, output: OutputStream) {
        DataOutputStream(output.buffered()).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeInt(templates.size)
            for (template in templates) {
                out.writeUTF(template.phrase)
                out.writeUTF(template.skillId)
                out.writeUTF(template.source)
                out.writeInt(template.params.size)
                for ((key, value) in template.params) {
                    out.writeUTF(key)
                    out.writeUTF(value)
                }
                out.writeInt(template.features.size)
                out.writeInt(template.features.dimension)
                for (frame in template.features.frames) {
                    for (value in frame) out.writeFloat(value.toFloat())
                }
            }
            out.flush()
        }
    }

    fun read(input: InputStream): List<PhraseTemplate> {
        DataInputStream(input.buffered()).use { source ->
            if (source.readInt() != MAGIC) throw IOException("not an alf template file")
            val version = source.readInt()
            if (version != VERSION) throw IOException("template file version $version, expected $VERSION")

            val count = source.readInt()
            if (count < 0) throw IOException("negative template count")

            return List(count) {
                val phrase = source.readUTF()
                val skillId = source.readUTF()
                val sourceName = source.readUTF()
                val paramCount = source.readInt()
                val params = buildMap(paramCount) {
                    repeat(paramCount) { put(source.readUTF(), source.readUTF()) }
                }
                val frameCount = source.readInt()
                val dimension = source.readInt()
                val frames = List(frameCount) {
                    DoubleArray(dimension) { source.readFloat().toDouble() }
                }
                PhraseTemplate(phrase, skillId, params, FeatureSequence(frames), sourceName)
            }
        }
    }
}
