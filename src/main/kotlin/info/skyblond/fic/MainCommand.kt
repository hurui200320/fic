package info.skyblond.fic

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.terminal
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
import java.util.concurrent.*

class MainCommand : CliktCommand(
    name = "fic"
) {
    private val parallelism: Int by option(
        "-p",
        "--parallelism"
    ).help("Decide the threadpool size when calculating the checksum").int()
        .defaultLazy { (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1) }

    private val append: Boolean by option("-a", "--append")
        .help("Append new files: calculate file hash and add to the record").flag()
    private val update: Boolean by option("-u", "--update")
        .help("Update modified files: replace record with newly calculated hash").flag()
    private val remove: Boolean by option("-r", "--remove")
        .help("Remove deleted files: delete file record").flag()
    private val verify: Boolean by option("-v", "--verify")
        .help(
            "Verify existing non-modified files: calculate file hash and compare with record, " +
                    "if and only if the file is not modified " +
                    "(by checking file length and last modified time)"
        ).flag()

    private val folders: List<File> by argument(name = "folder").file(
        mustExist = true,
        canBeFile = false,
        canBeDir = true
    ).multiple().help("The data folder")

    /**
     * The thread pool for calculating hash.
     *
     * @see java.util.concurrent.Executors.newFixedThreadPool
     * */
    private val hashPool by lazy {
        ThreadPoolExecutor(
            parallelism, parallelism,
            0L, TimeUnit.MILLISECONDS,
            LinkedBlockingQueue(),
            // we want this as daemon
            { r -> Thread(r).apply { isDaemon = true } },
            ThreadPoolExecutor.CallerRunsPolicy()
        )
    }

    override fun run() {
        info { testB3sum() }
        logOptions()
        val folderQueue = LinkedList<File>()
        folderQueue.addAll(folders)
        // this is essentially a snapshot.
        // we will grab all folders and files as quick as possible,
        // then process them later
        while (folderQueue.isNotEmpty()) {
            val dir = folderQueue.removeFirst()
            debug { "Visiting folder: $dir" }
            folderQueue.addAll(
                0, dir.listFiles(FileFilter {
                    it.isDirectory && !it.name.startsWith(".")
                })?.toList()?.sortedBy { it.name } ?: emptyList()
            )
            val files = dir.listFiles(FileFilter {
                it.isFile && !it.name.startsWith(".") && it.name != CHECKSUM_FILENAME
            })?.toList()?.sortedBy { it.name } ?: emptyList()
            processFiles(dir, files)
        }
        // shutdown hash pool
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
                else throw CliktError(terminal.theme.danger("New file is not allowed: $f"))
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
            else throw CliktError(terminal.theme.danger("Delete file is not allowed: $f"))
        }
        // calculate hash for new files
        newFiles.forEach { f ->
            newChecksumFutures.add(CompletableFuture.supplyAsync({
                info { "Calculating checksum for new file: $f" }
                val st = System.currentTimeMillis()
                ChecksumRecord(
                    filename = f.name,
                    hash = f.b3sum()
                ).also {
                    val et = System.currentTimeMillis()
                    info {
                        "New file hash ${it.hash} for $f " +
                                "(${"%.2f".format(f.calculateRate(st, et))}MB/s)"
                    }
                }
            }, hashPool))
        }
        // Process old files
        oldFiles.forEach { (f, oldChecksum) ->
            newChecksumFutures.add(CompletableFuture.supplyAsync({
                if (verify || update) {
                    info { "Calculating checksum for file: $f" }
                    val st = System.currentTimeMillis()
                    val actualHash = f.b3sum()
                    val et = System.currentTimeMillis()
                    if (actualHash != oldChecksum.hash) {
                        if (update) { // replace record
                            return@supplyAsync ChecksumRecord(
                                filename = f.name,
                                hash = actualHash,
                            ).also {
                                info {
                                    "Updated file hash ${it.hash} for $f " +
                                            "(${"%.2f".format(f.calculateRate(st, et))}MB/s)"
                                }
                            }
                        } else { // update is not allowed
                            throw CliktError( // break the normal flow
                                terminal.theme.danger(
                                    "Hash mismatch and update is not allowed: \n" +
                                            "\texpect: ${oldChecksum.hash}\n" +
                                            "\tactual: $actualHash\n" +
                                            "\tfile: $f"
                                )
                            )
                        }
                    } else { // verify ok
                        info {
                            "Verified hash $actualHash for $f " +
                                    "(${"%.2f".format(f.calculateRate(st, et))}MB/s)"
                        }
                        return@supplyAsync ChecksumRecord(
                            filename = f.name,
                            hash = actualHash,
                        )
                    }
                }
                // no verify and no update,
                // or verify ok.
                // return the old value
                return@supplyAsync oldChecksum
            }, hashPool))
        }
        // save to file
        val result = newChecksumFutures.map {
            try {
                it.get()
            } catch (e: ExecutionException) {
                // use cause if available, this will unwrap the execution exception
                throw e.cause ?: e
            }
        }.sortedBy { it.filename }
        debug { "Saving result for folder $dir" }
        saveChecksumFileInFolder(dir, result)
        saveChecksumFileInFolder(dir, result)
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

    private fun loadChecksumFileInFolder(folder: File): Set<ChecksumRecord> =
        File(folder, CHECKSUM_FILENAME).let { file ->
            if (file.exists()) {
                if (file.isFile) {
                    file.useLines { lines ->
                        lines.filterNot { it.startsWith("#") }
                            .mapNotNull { ChecksumRecord.deserialize(it) }.toSet()
                    }
                } else {
                    throw CliktError(terminal.theme.danger("Checksum file for folder $folder is not a file"))
                }
            } else {
                debug { "Checksum file doesn't exist in $folder" }
                emptySet()
            }
        }

    private fun logOptions() {
        info { "Parallelism: $parallelism" }
        if (append) {
            warn { "Will calculate new files and append to the record" }
        } else {
            warn { "Will throw error on new files" }
        }
        if (update) {
            warn { "Will replace record with new hash" }
        } else {
            warn { "Will throw error if file hash doesn't match record" }
        }
        if (remove) {
            warn { "Will remove records for deleted files" }
        } else {
            warn { "Will throw error on deleted files" }
        }
        if (verify) {
            warn { "Will verify the hash of existing files" }
        } else {
            warn { "Will NOT verify the hash of existing files" }
        }
    }

    companion object {
        private const val CHECKSUM_FILENAME = ".fic"
    }
}