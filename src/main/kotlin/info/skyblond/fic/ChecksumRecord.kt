package info.skyblond.fic

data class ChecksumRecord(
    val filename: String,
    val size: Long,
    val lastModified: Long,
    val hash: String
) {
    fun serialize(): String = "$hash $size $lastModified $filename"

    companion object {
        fun deserialize(str: String): ChecksumRecord {
            val (hash, size, lastModified, filename) = str.split(" ", limit = 4)
            return ChecksumRecord(
                filename = filename,
                size = size.toLong(),
                lastModified = lastModified.toLong(),
                hash = hash
            )
        }
    }
}