package dev.alf.llm

import dev.alf.domain.SkillDefinition
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Builds the request that asks the model what the speaker wanted.
 *
 * The tool list is generated from the same [SkillDefinition]s the offline matcher and the rule
 * based resolver are built from. Nothing about a skill is written twice: add one to the catalog
 * and the model can call it, with the description and parameters it already carries.
 */
object GeminiRequest {

    /**
     * The assistant is told to prefer calling a skill and to keep replies short, because whatever
     * comes back is spoken aloud — a paragraph that reads well is tiring to listen to.
     */
    const val SYSTEM_INSTRUCTION: String =
        "Sen alf adında bir Türkçe sesli ev asistanısın. Cevapların yüksek sesle okunacak, " +
            "bu yüzden kısa ve sade konuş; madde işareti, biçimlendirme veya emoji kullanma. " +
            "Kullanıcının isteği tanımlı işlevlerden biriyle karşılanabiliyorsa mutlaka o işlevi " +
            "çağır. Karşılanamıyorsa bir iki cümleyle cevap ver. Bilmediğin bir şeyi uydurma."

    fun forText(
        transcript: String,
        skills: List<SkillDefinition>,
        systemInstruction: String = SYSTEM_INSTRUCTION,
    ): String = build(
        parts = buildJsonArray { add(buildJsonObject { put("text", transcript) }) },
        skills = skills,
        systemInstruction = systemInstruction,
    )

    /**
     * [audioBase64] is the captured utterance. Sending audio rather than a transcript keeps the
     * whole fallback to a single round trip, and spares the assistant a separate speech service.
     */
    fun forAudio(
        audioBase64: String,
        skills: List<SkillDefinition>,
        mimeType: String = "audio/wav",
        systemInstruction: String = SYSTEM_INSTRUCTION,
    ): String = build(
        parts = buildJsonArray {
            add(
                buildJsonObject {
                    putJsonObject("inlineData") {
                        put("mimeType", mimeType)
                        put("data", audioBase64)
                    }
                },
            )
        },
        skills = skills,
        systemInstruction = systemInstruction,
    )

    /** The reply to a function call, so the model can turn the result into a sentence. */
    fun forFunctionResult(
        previousUserPart: JsonObject,
        functionName: String,
        result: String,
        skills: List<SkillDefinition>,
        systemInstruction: String = SYSTEM_INSTRUCTION,
    ): String = buildJsonObject {
        putJsonObject("systemInstruction") {
            putJsonArray("parts") { add(buildJsonObject { put("text", systemInstruction) }) }
        }
        putJsonArray("contents") {
            add(
                buildJsonObject {
                    put("role", "user")
                    putJsonArray("parts") { add(previousUserPart) }
                },
            )
            add(
                buildJsonObject {
                    put("role", "function")
                    putJsonArray("parts") {
                        add(
                            buildJsonObject {
                                putJsonObject("functionResponse") {
                                    put("name", functionName)
                                    putJsonObject("response") { put("result", result) }
                                }
                            },
                        )
                    }
                },
            )
        }
        put("tools", toolsOf(skills))
        putJsonObject("generationConfig") {
            put("temperature", 0.2)
            put("maxOutputTokens", MAX_OUTPUT_TOKENS)
        }
    }.toString()

    private fun build(parts: JsonArray, skills: List<SkillDefinition>, systemInstruction: String): String =
        buildJsonObject {
            putJsonObject("systemInstruction") {
                putJsonArray("parts") { add(buildJsonObject { put("text", systemInstruction) }) }
            }
            putJsonArray("contents") {
                add(
                    buildJsonObject {
                        put("role", "user")
                        put("parts", parts)
                    },
                )
            }
            put("tools", toolsOf(skills))
            putJsonObject("generationConfig") {
                put("temperature", 0.2)
                put("maxOutputTokens", MAX_OUTPUT_TOKENS)
            }
        }.toString()

    /** One `functionDeclarations` block holding every skill the model may call. */
    fun toolsOf(skills: List<SkillDefinition>): JsonArray = buildJsonArray {
        add(
            buildJsonObject {
                putJsonArray("functionDeclarations") {
                    skills.forEach { add(declarationOf(it)) }
                }
            },
        )
    }

    private fun declarationOf(skill: SkillDefinition): JsonObject = buildJsonObject {
        put("name", skill.id)
        put("description", skill.description)
        if (skill.parameters.isNotEmpty()) {
            putJsonObject("parameters") {
                put("type", "OBJECT")
                putJsonObject("properties") {
                    skill.parameters.forEach { parameter ->
                        putJsonObject(parameter.name) {
                            put("type", "STRING")
                            put("description", parameter.description)
                        }
                    }
                }
                val required = skill.parameters.filter { it.required }.map { it.name }
                if (required.isNotEmpty()) {
                    putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
                }
            }
        }
    }

    private const val MAX_OUTPUT_TOKENS = 512
}
