package scala.meta.internal.metals

import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.file.Files

import scala.collection.mutable.ArrayBuffer
import scala.util.control.NonFatal

import scala.meta.internal.io.FileIO
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath

import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range

class FindTextInDependencyJars(
    buildTargets: BuildTargets,
    workspace: () => AbsolutePath
) {
  def find(mask: String, content: String): List[Location] = {
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
            s"Failed to find text in dependency files for $classpathEntry",
            e
          )
      }
    }

    allLocations.toList
  }

  private def isSuitableFile(path: AbsolutePath, mask: String): Boolean = {
    path.isFile && path.filename.contains(mask)
  }

  private def visitJar(
      path: AbsolutePath,
      mask: String,
      content: String
  ): List[Location] = {
    FileIO
      .withJarFileSystem(path, create = false, close = true) { root =>
        FileIO
          .listAllFilesRecursively(root)
          .filter(isSuitableFile(_, mask))
          .flatMap { absPath =>
            val fileRanges: List[Range] = visitFileInsideJar(absPath, content)
            if (fileRanges.nonEmpty) {
              val result = absPath.toFileOnDisk(workspace())
              fileRanges
                .map(range => new Location(result.toURI.toString, range))
            } else Nil
          }
      }
      .toList
  }

  private def visitFileInsideJar(path: AbsolutePath, content: String): List[Range] = {
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
