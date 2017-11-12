package scala.meta.languageserver.ctags

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.text.DecimalFormat
import java.text.NumberFormat
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream
import scala.collection.GenSeq
import scala.collection.parallel.mutable.ParArray
import scala.meta.parsers.ParseException
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
import org.langmeta.semanticdb.Document

/**
 * Syntactically build a semanticdb index containing only global symbol definition.
 *
 * The purpose of this module is to provide "Go to definition" from
 * project sources to dependency sources without indexing classfiles or
 * requiring dependencies to publish semanticdbs alongside their artifacts.
 *
 * One other use-case for this module is to implement "Workspace symbol provider"
 * without any build-tool or compiler integration. Essentially, ctags.
 */
object Ctags extends LazyLogging {

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
    val fragments = allClasspathFragments(classpath)
    val totalIndexedFiles = new AtomicInteger()
    val totalIndexedLines = new AtomicInteger()
    val start = System.nanoTime()
    def elapsed: Long =
      TimeUnit.SECONDS.convert(System.nanoTime() - start, TimeUnit.NANOSECONDS)
    val decimal = new DecimalFormat("###.###")
    val N = fragments.length
    def updateTotalLines(doc: Document): Unit = doc.input match {
      case Input.VirtualFile(_, contents) =>
        // NOTE(olafur) it would be interesting to have separate statistics for
        // Java/Scala lines/s but that would require a more sophisticated setup.
        totalIndexedLines.addAndGet(countLines(contents))
      case _ =>
    }
    def reportProgress(indexedFiles: Int): Unit = {
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
        case _: ParseException => // nothing
        case NonFatal(e) =>
          logger.error(e.getMessage, e)
      }
    }
  }

  /** Index single Scala or Java source file from memory */
  def index(filename: String, contents: String): Document =
    index(Input.VirtualFile(filename, contents))

  /** Index single Scala or Java from disk or zip file. */
  def index(fragment: Fragment): Document = {
    val filename = fragment.uri.toString
    val contents =
      new String(FileIO.readAllBytes(fragment.uri), StandardCharsets.UTF_8)
    index(Input.VirtualFile(filename, contents))
  }

  /** Index single Scala or Java source file from memory */
  def index(input: Input.VirtualFile): Document = {
    logger.trace(s"Indexing ${input.path} with length ${input.value.length}")
    val indexer: CtagsIndexer =
      if (isScala(input.path)) ScalaCtags.index(input)
      else if (isJava(input.path)) QDoxCtags.index(input)
      else {
        throw new IllegalArgumentException(
          s"Unknown file extension ${input.path}"
        )
      }
    val (names, symbols) = indexer.index()
    Document(
      input,
      indexer.language,
      names,
      Nil,
      symbols,
      Nil
    )
  }

  private def canIndex(path: String): Boolean =
//    isScala(path) ||
    isJava(path)
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
  ): ParArray[Fragment] = {
    var buf = ParArray.newBuilder[Fragment]
    classpath.foreach { base =>
      def exploreJar(base: AbsolutePath): Unit = {
        val stream = Files.newInputStream(base.toNIO)
        try {
          val zip = new ZipInputStream(stream)
          try {
            var entry = zip.getNextEntry
            while (entry != null) {
              if (!entry.getName.endsWith("/") && canIndex(entry.getName)) {
                val name = RelativePath(entry.getName.stripPrefix("/"))
                buf += Fragment(base, name)
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
                buf += Fragment(base, RelativePath(base.toNIO.relativize(file)))
              }
              FileVisitResult.CONTINUE
            }
          }
        )
      } else if (base.isFile) {
        if (base.toString.endsWith(".jar")) {
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
