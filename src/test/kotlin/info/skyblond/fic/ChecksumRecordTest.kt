package info.skyblond.fic

import org.junit.jupiter.api.Assertions.*

import org.junit.jupiter.api.Test

class ChecksumRecordTest {

    @Test
    fun serialize() {
        val obj = ChecksumRecord(
            filename = "test filename",
            size = 1234L,
            hash = "hash-here"
        )

        val serialized = obj.serialize()
        val recover = ChecksumRecord.deserialize(serialized)

        assertEquals(obj, recover)
    }
}