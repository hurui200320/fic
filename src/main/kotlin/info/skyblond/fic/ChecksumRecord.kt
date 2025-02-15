package info.skyblond.fic

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.util.*

@Serializable
data class ChecksumRecord(
    val filename: String,
    val hash: String
) {
    // use base64 to ensure one line
    fun serialize(): String = Base64.getEncoder().encodeToString(
        Json.encodeToString(serializer(), this).encodeToByteArray()
    )

    companion object {
        fun deserialize(str: String): ChecksumRecord? {
            return try {
                Json.decodeFromString(
                    serializer(),
                    Base64.getDecoder().decode(str).decodeToString()
                )
            } catch (e: SerializationException) {
                e.printStackTrace()
                null
            } catch (e: IllegalArgumentException) {
                e.printStackTrace()
                null
            }
        }
    }
}