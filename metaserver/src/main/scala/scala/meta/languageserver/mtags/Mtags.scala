package scala.meta.languageserver.mtags

import java.io.IOException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.text.DecimalFormat
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream
import scala.collection.parallel.mutable.ParArray
import scala.meta.parsers.ParseException
import com.thoughtworks.qdox.parser.{ParseException => QParseException}
import scala.reflect.ClassTag
import scala.util.Sorting
import scala.util.control.NonFatal
import com.typesafe.scalalogging.LazyLogging
import org.langmeta.inputs.Input
import org.langmeta.internal.io.FileIO
import org.langmeta.internal.io.PathIO
import org.langmeta.io.AbsolutePath
import org.langmeta.io.Fragment
import org.langmeta.io.RelativePath
import org.langmeta.internal.semanticdb.schema.Database
import org.langmeta.internal.semanticdb.schema.Document

/**
 * Syntactically build a semanticdb index containing only global symbol definition.
 *
 * The purpose of this module is to provide "Go to definition" from
 * project sources to dependency sources without indexing classfiles or
 * requiring dependencies to publish semanticdbs alongside their artifacts.
 *
 * One other use-case for this module is to implement "Workspace symbol provider"
 * without any build-tool or compiler integration. "Mtags" name comes from
 * mixing "meta" and "ctags".
 */
object Mtags extends LazyLogging {

  /**
   * Build an index from a classpath of -sources.jar
   *
   * @param shouldIndex An optional filter to skip particular relative filenames.
   * @param callback A callback that is called as soon as a document has been
   *                 indexed.
   */
  def index(
      classpath: List[AbsolutePath],
      shouldIndex: RelativePath => Boolean = _ => true
  )(callback: Document => Unit): Unit = {
    val fragments = allClasspathFragments(classpath, shouldIndex)
    val totalIndexedFiles = new AtomicInteger()
    val totalIndexedLines = new AtomicInteger()
    val start = System.nanoTime()
    def elapsed: Long = {
      val result = TimeUnit.SECONDS.convert(
        System.nanoTime() - start,
        TimeUnit.NANOSECONDS
      )
      if (result == 0) 1 // prevent divide by zero
      else result
    }
    val decimal = new DecimalFormat("###,###")
    val N = fragments.length
    def updateTotalLines(doc: Document): Unit = {
      // NOTE(olafur) it would be interesting to have separate statistics for
      // Java/Scala lines/s but that would require a more sophisticated setup.
      totalIndexedLines.addAndGet(countLines(doc.contents))
    }
    def reportProgress(indexedFiles: Int): Unit = {
      if (indexedFiles < 100) return
      val percentage = ((indexedFiles / N.toDouble) * 100).toInt
      val loc = decimal.format(totalIndexedLines.get() / elapsed)
      logger.info(
        s"Progress $percentage%, ${decimal.format(indexedFiles)} files indexed " +
          s"out of total ${decimal.format(fragments.length)} ($loc loc/s)"
      )
    }
    logger.info(s"Indexing $N source files")
    fragments.foreach { fragment =>
      try {
        val indexedFiles = totalIndexedFiles.incrementAndGet()
        if (indexedFiles % 200 == 0) {
          reportProgress(indexedFiles)
        }
        if (shouldIndex(fragment.name)) {
          val doc = index(fragment)
          updateTotalLines(doc)
          callback(doc)
        }
      } catch {
        case _: ParseException | _: QParseException => // nothing
        case NonFatal(e) =>
          logger.error(s"Error indexing ${fragment.syntax}", e)
      }
    }
    reportProgress(totalIndexedFiles.get)
    logger.info(
      s"Completed indexing ${decimal.format(totalIndexedFiles.get)} files with " +
        s"total ${decimal.format(totalIndexedLines.get())} lines of code"
    )
  }

  /** Index all documents into a single scala.meta.Database. */
  def indexDatabase(
      classpath: List[AbsolutePath],
      shouldIndex: RelativePath => Boolean = _ => true
  ): Database = {
    val buffer = List.newBuilder[Document]
    index(classpath, shouldIndex) { doc =>
      buffer += doc
    }
    Database(buffer.result())
  }

  /** Index single Scala or Java source file from memory */
  def index(filename: String, contents: String): Document =
    index(Input.VirtualFile(filename, contents))

  /** Index single Scala or Java from disk or zip file. */
  def index(fragment: Fragment): Document = {
    val uri = {
      // Need special handling because https://github.com/scalameta/scalameta/issues/1163
      if (isZip(fragment.base.toNIO.getFileName.toString))
        new URI(s"jar:${fragment.base.toURI.normalize()}!/${fragment.name}")
      else fragment.uri
    }
    val contents = new String(FileIO.readAllBytes(uri), StandardCharsets.UTF_8)
    index(Input.VirtualFile(uri.toString, contents))
  }

  /** Index single Scala or Java source file from memory */
  def index(input: Input.VirtualFile): Document = {
    logger.trace(s"Indexing ${input.path} with length ${input.value.length}")
    val indexer: MtagsIndexer =
      if (isScala(input.path)) ScalaMtags.index(input)
      else if (isJava(input.path)) JavaMtags.index(input)
      else {
        throw new IllegalArgumentException(
          s"Unknown file extension ${input.path}"
        )
      }
    val (names, symbols) = indexer.index()
    Document(
      filename = input.path,
      contents = input.value,
      language = indexer.language,
      names,
      Nil,
      symbols,
      Nil
    )
  }

  private def canIndex(path: String): Boolean =
    isScala(path) || isJava(path)
  private def canUnzip(path: String): Boolean =
    isJar(path) || isZip(path)
  private def isJar(path: String): Boolean = path.endsWith(".jar")
  private def isZip(path: String): Boolean = path.endsWith(".zip")
  private def isJava(path: String): Boolean = path.endsWith(".java")
  private def isScala(path: String): Boolean = path.endsWith(".scala")
  private def isScala(path: Path): Boolean = PathIO.extension(path) == "scala"

  /** Returns all *.scala fragments to index from this classpath
   *
   * This implementation is copy-pasted from scala.meta.Classpath.deep with
   * the following differences:
   *
   * - We build a parallel array
   * - We log errors instead of silently ignoring them
   * - We filter out non-scala sources
   */
  private def allClasspathFragments(
      classpath: List[AbsolutePath],
      shouldIndex: RelativePath => Boolean
  ): ParArray[Fragment] = {
    val buf = ParArray.newBuilder[Fragment]
    def add(fragment: Fragment): Unit = {
      if (shouldIndex(fragment.name)) buf += fragment
      else ()
    }
    classpath.foreach[Any] { base =>
      def exploreJar(base: AbsolutePath): Unit = {
        val stream = Files.newInputStream(base.toNIO)
        try {
          val zip = new ZipInputStream(stream)
          try {
            var entry = zip.getNextEntry
            while (entry != null) {
              if (!entry.getName.endsWith("/") &&
                canIndex(entry.getName)) {
                add(
                  Fragment(base, RelativePath(entry.getName.stripPrefix("/")))
                )
              }
              entry = zip.getNextEntry
            }
          } finally zip.close()
        } catch {
          case ex: IOException =>
            logger.error(ex.getMessage, ex)
        } finally {
          stream.close()
        }
      }
      if (base.isDirectory) {
        Files.walkFileTree(
          base.toNIO,
          new SimpleFileVisitor[Path] {
            override def visitFile(
                file: Path,
                attrs: BasicFileAttributes
            ): FileVisitResult = {
              if (isScala(file)) {
                add(Fragment(base, RelativePath(base.toNIO.relativize(file))))
              }
              FileVisitResult.CONTINUE
            }
          }
        )
      } else if (base.isFile) {
        if (canUnzip(base.toString())) {
          exploreJar(base)
        } else {
          sys.error(
            s"Obtained non-jar file $base. Expected directory or *.jar file."
          )
        }
      } else {
        logger.info(s"Skipping $base")
        // Skip
      }
    }
    val result = buf.result()
    Sorting.stableSort(result.arrayseq)(
      implicitly[ClassTag[Fragment]],
      Ordering.by { fragment =>
        PathIO.extension(fragment.name.toNIO) match {
          case "scala" => 1
          case "java" => 2
          case _ => 3
        }
      }
    )
    result
  }

  private def countLines(string: String): Int = {
    var i = 0
    var lines = 0
    while (i < string.length) {
      if (string.charAt(i) == '\n') lines += 1
      i += 1
    }
    lines
  }
}
