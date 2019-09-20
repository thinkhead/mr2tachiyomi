/*
 * GPLv3 License
 *
 *  Copyright (c) mr2tachiyomi by waicool20
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.waicool20.mr2tachiyomi

import com.fasterxml.jackson.dataformat.csv.CsvMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.waicool20.mr2tachiyomi.models.Source
import com.waicool20.mr2tachiyomi.models.csv.CsvManga
import com.waicool20.mr2tachiyomi.models.database.*
import com.waicool20.mr2tachiyomi.models.json.TachiyomiBackup
import com.waicool20.mr2tachiyomi.models.json.TachiyomiChapter
import com.waicool20.mr2tachiyomi.models.json.TachiyomiManga
import com.waicool20.mr2tachiyomi.util.ABUtils
import com.waicool20.mr2tachiyomi.util.TarHeaderOffsets
import com.waicool20.mr2tachiyomi.util.toStringAndTrim
import org.apache.commons.cli.DefaultParser
import org.apache.commons.cli.HelpFormatter
import org.apache.commons.cli.Options
import org.apache.commons.cli.ParseException
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import tornadofx.launch
import java.io.FileNotFoundException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess

private val options = Options().apply {
    addOption("i", "input", true, "input file to convert")
    addOption("o", "output", true, "output file")
    addOption("h", "help", false, "print help message")
}

private val logger = LoggerFactory.getLogger("Main")

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        launch<UserInterface>(args)
        exitProcess(0)
    }

    val input: Path
    val output: Path

    try {
        val command = DefaultParser().parse(options, args)
        if (command.hasOption('h')) {
            printHelp(options)
            return
        }

        input = Paths.get(command.getOptionValue('i') ?: "mangarock.db")
        output = Paths.get(command.getOptionValue('o') ?: "output.json")
    } catch (e: ParseException) {
        logger.error("Failed to parse args: " + e.message)
        printHelp(options)
        exitProcess(1)
    }
    MR2Tachiyomi.convert(input, output)
}

fun printHelp(options: Options) {
    val formatter = HelpFormatter()
    formatter.printHelp("mr2tachiyomi", options)
}

object MR2Tachiyomi {
    private val logger = LoggerFactory.getLogger(javaClass)

    sealed class Result {
        class ConversionComplete(val success: List<Favorite>, val failed: List<Favorite>) : Result()
        class FailedWithException(val exception: Exception) : Result()
    }

    class UnsupportedFileFormatException(format: String) : Exception("Unsupported File Format: $format")

    fun convert(input: Path, output: Path): Result {
        return try {
            if (Files.notExists(input)) return Result.FailedWithException(FileNotFoundException("File $input not found!"))

            val database = when {
                "$input".endsWith(".db") -> input
                "$input".endsWith(".ab") -> extractDbFromAb(input)
                else -> throw UnsupportedFileFormatException("$input".takeLastWhile { it != '.' })
            }

            when (val extension = "$output".takeLastWhile { it != '.' }) {
                "json" -> convertToTachiyomiJson(database, output)
                "csv" -> convertToCsv(database, output)
                else -> throw UnsupportedFileFormatException(extension)
            }
        } catch (e: Exception) {
            logger.error("Could not convert database file to due to unknown exception", e)
            e.printStackTrace()
            Result.FailedWithException(e)
        }
    }

    private fun convertToTachiyomiJson(database: Path, output: Path): Result {
        Database.connect("jdbc:sqlite:file:$database", driver = "org.sqlite.JDBC")
        return transaction {
            SchemaUtils.create(
                Favorites,
                MangaChapters,
                MangaChapterLocals
            )

            val (convertible, nonConvertible) = Favorite.all().partition {
                try {
                    it.source
                    true
                } catch (e: Source.UnsupportedSourceException) {
                    logger.warn("Cannot process manga ( $it ): ${e.message}")
                    false
                }
            }

            logger.info("-----------------")

            convertible.map { fav ->
                TachiyomiManga(
                    fav.source.getMangaUrl(),
                    fav.mangaName,
                    fav.source.TachiyomiId,
                    chapters = MangaChapter.find { MangaChapters.mangaId eq fav.id.value }
                        .map {
                            TachiyomiChapter(
                                fav.source.getChapterUrl(it),
                                it.local?.read ?: 0
                            )
                        }
                ).also { logger.info("Processed $fav") }
            }.let {
                jacksonObjectMapper().writerWithDefaultPrettyPrinter()
                    .writeValue(output.toFile(), TachiyomiBackup(it))
            }
            logger.info("-----------------")
            logger.info("Succesfully processed ${convertible.size} manga; Failed to process ${nonConvertible.size} manga")
            Result.ConversionComplete(convertible, nonConvertible)
        }
    }

    private fun convertToCsv(database: Path, output: Path): Result {
        Database.connect("jdbc:sqlite:file:$database", driver = "org.sqlite.JDBC")
        return transaction {
            SchemaUtils.create(
                Favorites,
                MangaChapters,
                MangaChapterLocals
            )
            val favs = Favorite.all().toList()
            favs.map { fav ->
                val (read, unread) = MangaChapter.find { MangaChapters.mangaId eq fav.id.value }
                    .partition { it.local?.read == 1 }
                CsvManga(fav.mangaName, fav.author, read, unread)
                    .also { logger.info("Processed $fav") }
            }.let {
                val mapper = CsvMapper()
                val schema = mapper.schemaFor(CsvManga::class.java).withHeader()
                mapper.writer(schema).writeValue(output.toFile(), it)
            }
            logger.info("-----------------")
            logger.info("Succesfully processed ${favs.size} manga")
            Result.ConversionComplete(favs, emptyList())
        }
    }

    private fun extractDbFromAb(input: Path): Path {
        val tarFile = input.resolveSibling("${input.toFile().nameWithoutExtension}.tar")
        val db = input.resolveSibling("mangarock.db")
        val buffer = ByteArray(512)

        ABUtils.ab2tar(input, tarFile)
        Files.newInputStream(tarFile).use { inputStream ->
            while (inputStream.available() > 0) {
                inputStream.read(buffer)
                val name = buffer.sliceArray(TarHeaderOffsets.NAME_RANGE).toStringAndTrim()
                if (name == "apps/com.notabasement.mangarock.android.lotus/db/mangarock.db") {
                    val size = buffer.sliceArray(TarHeaderOffsets.SIZE_RANGE).toStringAndTrim().toInt(8)
                    val blocks = if (size > 0) 1 + (size - 1) / 512 else 0
                    Files.newOutputStream(db).use { outputStream ->
                        repeat(blocks) {
                            inputStream.read(buffer)
                            outputStream.write(buffer)
                        }
                    }
                    break
                }
            }
        }
        return db
    }
}
