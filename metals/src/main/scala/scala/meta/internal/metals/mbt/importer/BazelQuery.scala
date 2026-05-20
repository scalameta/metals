package scala.meta.internal.metals.mbt.importer

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.builds.BazelProjectViewTargets
import scala.meta.internal.builds.ShellRunner
import scala.meta.internal.process.ExitCodes
import scala.meta.io.AbsolutePath

object BazelQuery {
  case class Env(
      projectRoot: AbsolutePath,
      shellRunner: ShellRunner,
      javaHome: Option[String],
  )

  sealed abstract class OutputMode(name: String) {
    override def toString(): String = name
  }
  object OutputMode {
    case object Label extends OutputMode("label")
    case object Xml extends OutputMode("xml")
  }
  import OutputMode._

  def buildRuleKindsQuery(patterns: List[String]): BazelQuery = {
    val ps =
      if (patterns.isEmpty) BazelProjectViewTargets.defaultPatterns
      else patterns
    val parts = for {
      k <- ruleKinds
      p <- ps
    } yield s"kind($k, $p)"
    val query = parts.mkString(" union ")
    BazelQuery(query, outputMode = Label)
  }

  def fullInformationQuery(targets: List[String]): BazelQuery = {
    val query = s"deps(set(${targets.mkString(" ")}))"
    BazelQuery(query, outputMode = Xml)
  }

  def allScalaLibrariesQuery: BazelQuery =
    BazelQuery("filter('scala.library', deps(//...))", outputMode = Label)

  private val ruleKinds: List[String] =
    List(
      "scala_library", "java_library", "scala_binary", "java_binary",
      "scala_test", "java_test",
    )

}

case class BazelQuery(
    query: String,
    outputMode: BazelQuery.OutputMode,
) {
  import BazelQuery.Env

  def run(
      env: Env
  )(implicit ec: ExecutionContext): Future[String] = {
    import env._
    val buf = new StringBuilder()
    shellRunner
      .run(
        "bazel-mbt-query",
        List(
          "bazel",
          "query",
          query,
          s"--output=$outputMode",
          "--keep_going",
        ),
        projectRoot,
        redirectErrorOutput = false,
        javaHome,
        processOut = line => {
          buf.append(line)
          buf.append(System.lineSeparator())
        },
        processErr = scribe.warn(_),
      )
      .future
      .flatMap {
        case ExitCodes.Success =>
          Future.successful(buf.toString)
        case ExitCodes.Cancel =>
          Future.failed(
            new java.util.concurrent.CancellationException(
              "bazel-mbt: query cancelled"
            )
          )
        case code =>
          scribe.warn(
            s"bazel-mbt: bazel query failed with exit code $code, but might be unreleated."
          )
          Future.successful(buf.toString)
      }
  }

}
