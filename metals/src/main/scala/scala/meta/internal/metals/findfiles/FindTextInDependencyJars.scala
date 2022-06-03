package scala.meta.internal.metals.findfiles

import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.PathMatcher
import java.util.stream.Collectors

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.clients.language.MetalsInputBoxParams
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.internal.metals.filesystem.MetalsFileSystem
import scala.meta.io.AbsolutePath

import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range

class FindTextInDependencyJars(
    buildTargets: BuildTargets,
    workspace: () => AbsolutePath,
    languageClient: MetalsLanguageClient,
    saveJarFileToDisk: Boolean
)(implicit ec: ExecutionContext) {
  import FindTextInDependencyJars._

  def find(request: FindTextInDependencyJarsRequest): Future[List[Location]] = {
    val req = Request.fromRequest(request)

    def readInclude: Future[Option[String]] =
      paramOrInput(req.options.flatMap(_.include))(
        MetalsInputBoxParams(value = ".conf", prompt = "Enter file mask")
      )

    def readPattern: Future[Option[String]] =
      paramOrInput(Option(req.query.pattern))(
        MetalsInputBoxParams(prompt = "Enter content to search for")
      )

    readInclude.zipWith(readPattern) { (maybeInclude, maybePattern) =>
      maybeInclude
        .zip(maybePattern)
        .map { case (include, pattern) =>
          val allLocations = mutable.ArrayBuffer.empty[Location]
          val mfs = MetalsFileSystem.metalsFS
          val includeMatcher = mfs.getPathMatcher(s"glob:**$include")
          val excludeMatcher = req.options
            .flatMap(_.exclude)
            .map(e => mfs.getPathMatcher(s"glob:**$e"))

          val locations = Files
            .walk(mfs.rootPath)
            .filter(isSuitableFile(_, includeMatcher, excludeMatcher))
            .collect(Collectors.toList())
            .asScala
            .flatMap { path =>
              val fileRanges: List[Range] = visitFile(path, pattern)
              if (fileRanges.isEmpty)
                Nil
              else {
                val locationPath =
                  if (saveJarFileToDisk)
                    AbsolutePath(path).toFileOnDisk(workspace())
                  else AbsolutePath(path)
                fileRanges
                  .map(range =>
                    new Location(locationPath.toNIO.toUri.toString, range)
                  )
              }
            }
          allLocations ++= locations
          allLocations.toList
        }
        .toList
        .flatten
    }
  }

  private def isSuitableFile(
      path: Path,
      includeMatcher: PathMatcher,
      excludeMatcher: Option[PathMatcher]
  ): Boolean = {
    Files.isRegularFile(path) &&
    includeMatcher.matches(path) &&
    excludeMatcher.forall(matcher => !matcher.matches(path))
  }

  private def visitFile(
      path: Path,
      pattern: String
  ): List[Range] = {
    var reader: BufferedReader = null
    val positions = mutable.ArrayBuffer.empty[Int]
    val results = mutable.ArrayBuffer.empty[Range]
    val contentLength: Int = pattern.length()

    try {
      reader = new BufferedReader(
        new InputStreamReader(Files.newInputStream(path))
      )
      var lineNumber: Int = 0
      var line: String = reader.readLine()
      while (line != null) {
        var occurence = line.indexOf(pattern)
        while (occurence != -1) {
          positions += occurence
          occurence = line.indexOf(pattern, occurence + 1)
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

  private def paramOrInput(
      param: Option[String]
  )(input: => MetalsInputBoxParams): Future[Option[String]] = {
    param match {
      case Some(value) =>
        Future.successful(Some(value))
      case None =>
        languageClient
          .metalsInputBox(input)
          .asScala
          .flatMapOptionInside {
            case name if name.value.nonEmpty => Some(name.value)
            case _ => None
          }
    }
  }
}

object FindTextInDependencyJars {
  // These are just more typesafe wrappers, duplicating the structure of original model
  private case class Request(options: Option[Options], query: TextSearchQuery)
  private object Request {
    def fromRequest(request: FindTextInDependencyJarsRequest): Request = {
      val options = Option(request.options).map { options =>
        Options(
          include = Option(options.include),
          exclude = Option(options.exclude)
        )
      }

      val query = TextSearchQuery(
        pattern = request.query.pattern,
        isRegExp = Option(request.query.isRegExp),
        isCaseSensitive = Option(request.query.isCaseSensitive),
        isWordMatch = Option(request.query.isWordMatch)
      )

      Request(options = options, query = query)
    }
  }

  private case class Options(include: Option[String], exclude: Option[String])
  private case class TextSearchQuery(
      pattern: String,
      isRegExp: Option[Boolean],
      isCaseSensitive: Option[Boolean],
      isWordMatch: Option[Boolean]
  )
}
