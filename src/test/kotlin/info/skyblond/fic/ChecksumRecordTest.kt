package info.skyblond.fic

import org.junit.jupiter.api.Assertions.*

import org.junit.jupiter.api.Test

class ChecksumRecordTest {

    @Test
    fun serialize() {
        val obj = ChecksumRecord(
            filename = "test filename",
            hash = "hash-here"
        )

        val serialized = obj.serialize()
        println(serialized)
        val recover = ChecksumRecord.deserialize(serialized)

        assertEquals(obj, recover)
    }
}