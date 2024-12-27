package info.skyblond.fic

data class ChecksumRecord(
    val filename: String,
    val size: Long,
    val hash: String
) {
    fun serialize(): String = "$hash $size $filename"

    companion object {
        fun deserialize(str: String): ChecksumRecord {
            val list = str.split(" ", limit = 4)
            if (list.size == 4) {
                // old format: hash size lastModify filename
                // now we removed the lastModify,
                // but still need to compatible with old fic file
                val (hash, size, _, filename) = list
                return ChecksumRecord(filename, size.toLong(), hash)
            } else {
                val (hash, size, filename) = list
                return ChecksumRecord(filename, size.toLong(), hash)
            }
        }
    }
}