package info.skyblond.fic

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.help
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.defaultLazy
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import info.skyblond.fic.SimpleLogger.debug
import info.skyblond.fic.SimpleLogger.info
import info.skyblond.fic.SimpleLogger.warn
import java.io.File
import java.io.FileFilter
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.Future

class MainCommand : CliktCommand(
    name = "fic"
) {
    private val parallelism: Int by option(
        "-p",
        "--parallelism"
    ).help("Decide the threadpool size when calculating the checksum").int()
        .defaultLazy { (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1) }

    private val append: Boolean by option("-a", "--append")
        .help("Append new files").flag()
    private val update: Boolean by option("-u", "--update")
        .help("Update modified files").flag()
    private val remove: Boolean by option("-r", "--remove")
        .help("Remove deleted files").flag()
    private val verify: Boolean by option("-v", "--verify")
        .help("Verify existing non-modified files").flag()

    private val folders: List<File> by argument(name = "folder").file(
        mustExist = true,
        canBeFile = false,
        canBeDir = true
    ).multiple().help("The data folder")

    private val folderPool by lazy { Executors.newVirtualThreadPerTaskExecutor() }
    private val hashPool by lazy { Executors.newFixedThreadPool(parallelism) }

    override fun run() {
        info { testB3sum() }
        logOptions()
        val folderQueue = LinkedList<File>()
        folderQueue.addAll(folders)
        val taskQueue = LinkedList<Future<*>>()
        // this is essentially a snapshot.
        // we will grab all folders and files as quick as possible,
        // then process them later
        while (folderQueue.isNotEmpty()) {
            val dir = folderQueue.removeFirst()
            debug { "Visiting folder: $dir" }
            folderQueue.addAll(
                0, dir.listFiles(FileFilter {
                    it.isDirectory && !it.name.startsWith(".")
                })?.toList() ?: emptyList()
            )
            val files = dir.listFiles(FileFilter {
                it.isFile && !it.name.startsWith(".") && it.name != CHECKSUM_FILENAME
            })?.toList() ?: emptyList()
            taskQueue.add(folderPool.submit { processFiles(dir, files) })
        }
        taskQueue.forEach {
            // find the cause (CliktError) and throw it
            var cause = kotlin.runCatching { it.get() }.exceptionOrNull()?.cause
            while (cause != null && cause !is CliktError) {
                cause = cause.cause
            }
            if (cause != null) {
                throw cause
            }
        }
        folderPool.shutdown()
        hashPool.shutdown()
    }

    private fun processFiles(dir: File, files: List<File>) {
        // existing checksum list
        val oldChecksums = loadChecksumFileInFolder(dir)
        // remaining list for deleted checksums
        val deletedChecksums = oldChecksums.toMutableSet()
        // the new checksum list
        val newChecksumFutures = mutableSetOf<CompletableFuture<ChecksumRecord>>()

        val newFiles = mutableListOf<File>()
        // for old files, since we're doing the calculation async,
        // we can't ensure the file is not modified when verifying it.
        // thus store them together.
        val oldFiles = mutableListOf<Pair<File, ChecksumRecord>>()
        // check all files on the disk
        files.forEach { f ->
            val checksum = oldChecksums.find { it.filename == f.name }
            if (checksum == null) { // new file
                if (append) newFiles.add(f)
                else throw CliktError("New file is not allowed: $f")
            } else {
                // remove from the deleted set
                deletedChecksums.remove(checksum)
                oldFiles.add(f to checksum)
            }
        }
        // check deleted files
        deletedChecksums.forEach { c ->
            val f = File(dir, c.filename)
            if (remove) warn { "Deleted file: $f" }
            else throw CliktError("Deleted file is not allowed: $f")
        }
        // calculate hash for new files
        newFiles.forEach { f ->
            newChecksumFutures.add(CompletableFuture.supplyAsync({
                ChecksumRecord(
                    filename = f.name,
                    size = f.length(),
                    lastModified = f.lastModified(),
                    hash = f.b3sum()
                ).also {
                    info { "New file hash ${it.hash} for $f" }
                }
            }, hashPool))
        }
        // Process old files
        oldFiles.forEach { (f, oldChecksum) ->
            newChecksumFutures.add(CompletableFuture.supplyAsync({
                if (f.lastModified() == oldChecksum.lastModified && f.length() == oldChecksum.size) {
                    verifyFile(f, oldChecksum)
                    return@supplyAsync oldChecksum
                } else {
                    // modify
                    if (!update) {
                        throw CliktError("Modified files not allow: $f")
                    }
                    f.createChecksum().also {
                        info { "Modified file hash ${it.hash} for $f" }
                    }
                }
            }, hashPool))
        }
        // TODO: Save to file
        saveChecksumFileInFolder(dir, newChecksumFutures.map { it.get() }.sortedBy { it.filename })
    }

    private fun saveChecksumFileInFolder(folder: File, checksums: List<ChecksumRecord>) {
        File(folder, CHECKSUM_FILENAME).printWriter().use { pw ->
            pw.println("# Generated at ${SimpleLogger.getTime()}")
            checksums.forEach {
                pw.println(it.serialize())
            }
            pw.flush()
        }
    }

    private fun File.createChecksum() = ChecksumRecord(
        filename = this.name,
        size = this.length(),
        lastModified = this.lastModified(),
        hash = this.b3sum()
    )

    private fun verifyFile(file: File, checksum: ChecksumRecord) {
        if (verify) {
            val actualHash = file.b3sum()
            if (actualHash != checksum.hash) {
                throw CliktError( // break the normal flow
                    "Hash mismatch: expect: ${checksum.hash}, actual: $actualHash, file: $file"
                )
            } else {
                info { "Verified hash $actualHash for $file" }
            }
        } else {
            warn { "Skip verify for $file" }
        }
    }

    private fun loadChecksumFileInFolder(folder: File): Set<ChecksumRecord> =
        File(folder, CHECKSUM_FILENAME).let { file ->
            if (file.exists()) {
                if (file.isFile) {
                    file.useLines { lines ->
                        lines.filterNot { it.startsWith("#") }
                            .map { ChecksumRecord.deserialize(it) }.toSet()
                    }
                } else {
                    throw CliktError("Checksum file for folder $folder does not exist")
                }
            } else {
                debug { "Checksum file doesn't exist in $folder" }
                emptySet()
            }
        }

    private fun logOptions() {
        info { "Parallelism: $parallelism" }
        if (append) {
            info { "Will calculate new files and append to the result" }
        } else {
            warn { "Will throw error on new files" }
        }
        if (update) {
            info { "Will update the hash of modified files" }
        } else {
            warn { "Will throw error on modified files" }
        }
        if (remove) {
            info { "Will remove deleted files" }
        } else {
            warn { "Will throw error on deleted files" }
        }
        if (verify) {
            info { "Will verify the hash of existing non-modified files" }
        } else {
            warn { "Will NOT verify the hash of existing non-modified files" }
        }
    }

    companion object {
        private const val CHECKSUM_FILENAME = ".fic"
    }
}