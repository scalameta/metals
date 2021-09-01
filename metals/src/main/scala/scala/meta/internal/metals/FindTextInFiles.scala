package scala.meta.internal.metals

import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.Position
import scala.collection.mutable.ArrayBuffer
import scala.meta.internal.metals.MetalsEnrichments._
import scala.util.control.NonFatal
import scala.meta.io.AbsolutePath
import scala.meta.internal.io.FileIO
import java.nio.file.Files
import java.nio.file.SimpleFileVisitor
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.FileVisitResult
import java.io.BufferedReader
import java.io.InputStreamReader

class FindTextInFiles(
    buildTargets: BuildTargets,
    workspace: () => AbsolutePath
) {
  def find(mask: String, content: String): List[Location] = {
    if (mask == null || content == null) {
      List.empty[Location]
    } else {
      val allLocations: ArrayBuffer[Location] = new ArrayBuffer[Location]()

      buildTargets.allWorkspaceJars.foreach { classpathEntry =>
        try {
          val jarLocations: List[Location] =
            if (classpathEntry.isFile && classpathEntry.isJar) {
              visitJar(classpathEntry, mask, content)
            } else Nil

          allLocations ++= jarLocations
        } catch {
          case NonFatal(e) =>
            scribe.error(
              s"Failed to find in non-source files for $classpathEntry",
              e
            )
        }
      }

      allLocations.toList
    }
  }

  def isSuitableFile(path: AbsolutePath, mask: String): Boolean = {
    path.isFile && path.filename.contains(mask)
  }

  def visitJar(
      path: AbsolutePath,
      mask: String,
      content: String
  ): List[Location] = {
    val jarLocations: ArrayBuffer[Location] = new ArrayBuffer[Location]()

    FileIO.withJarFileSystem(path, create = false, close = true) { root =>
      Files.walkFileTree(
        root.toNIO,
        new SimpleFileVisitor[Path] {
          override def visitFile(
              file: Path,
              attrs: BasicFileAttributes
          ): FileVisitResult = {
            val absPath = AbsolutePath(file)
            if (isSuitableFile(absPath, mask)) {
              val fileRanges: List[Range] = visitFileInsideJar(absPath, content)

              val fileLocations: List[Location] =
                if (fileRanges.nonEmpty) {
                  val result = absPath.toFileOnDisk(workspace())
                  fileRanges.map(range => new Location(result.toString, range))
                } else Nil

              jarLocations ++= fileLocations
            }

            FileVisitResult.CONTINUE
          }
        }
      )
    }

    jarLocations.toList
  }

  def visitFileInsideJar(path: AbsolutePath, content: String): List[Range] = {
    var reader: BufferedReader = null
    val positions: ArrayBuffer[Int] = new ArrayBuffer[Int]()
    val results: ArrayBuffer[Range] = new ArrayBuffer[Range]()
    val contentLength: Int = content.length()

    try {
      reader = new BufferedReader(
        new InputStreamReader(Files.newInputStream(path.toNIO))
      )
      var lineNumber: Int = 0
      var line: String = reader.readLine()
      while (line != null) {
        var occurence = line.indexOf(content)
        while (occurence != -1) {
          positions += occurence
          occurence = line.indexOf(content, occurence + 1)
        }

        positions.foreach { position =>
          results += new Range(
            new Position(lineNumber, position),
            new Position(lineNumber, position + contentLength)
          )
        }

        positions.clear()
        lineNumber = lineNumber + 1
        line = reader.readLine()
      }
    } finally {
      if (reader != null) reader.close()
    }

    results.toList
  }
}
