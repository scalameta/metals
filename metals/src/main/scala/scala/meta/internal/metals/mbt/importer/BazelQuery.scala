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
    case object Starlark extends OutputMode("starlark")
  }
  sealed abstract class QueryType(name: String) {
    override def toString(): String = name
  }
  object QueryType {
    case object Query extends QueryType("query")
    case object CQuery extends QueryType("cquery")
  }

  import OutputMode._, QueryType._

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

  /**
   * For any entry in `srcsByTarget` that is a Bazel rule (not a real source
   * file), runs `bazel cquery --output=files` on those rules and maps the
   * output file paths to `uncheckedSources` for the owning target.
   * Also returns the set of gen src labels so callers can exclude them from
   * `sources`.
   */
  def queryGenSrcOutputsByTarget(
      srcsByTarget: Map[String, List[String]],
      queryEnv: Env
  )(implicit ec: ExecutionContext): Future[(Map[String, List[String]], Set[String])] = {
    val allSrcLabels =
      srcsByTarget.values.flatten.filter(_.startsWith("//")).toSet
    if (allSrcLabels.isEmpty) {
      Future.successful((Map.empty[String, List[String]], Set.empty[String]))
    } else {
      // check if the labels correspond to source files, or rules
      BazelQuery(s"set(${allSrcLabels.mkString(" ")})", Xml).run(queryEnv)
        .flatMap { xml =>
          val targetsXmlDump = new BazelTargetsXmlDump(xml)
          val srcFiles = targetsXmlDump.sourceFileLabels
          val ruleLabels = allSrcLabels.filterNot(srcFiles)
          if (ruleLabels.isEmpty) {
            Future.successful((Map.empty[String, List[String]], Set.empty[String]))
          } else {
            runBazelCqueryOutputsByLabel(ruleLabels, queryEnv).map { outputsByGenLabel =>
              val genSrcOutputsByTarget = srcsByTarget.flatMap { case (target, srcs) =>
                val genPaths = srcs
                  .filter(ruleLabels)
                  .flatMap(outputsByGenLabel.getOrElse(_, Nil))
                Option.when(genPaths.nonEmpty)(target -> genPaths)
              }
              (genSrcOutputsByTarget, ruleLabels)
            }
          }
        }
        .recover { case e =>
          scribe.warn(s"bazel-mbt: failed to query generated source outputs", e)
          (Map.empty[String, List[String]], Set.empty[String])
        }
    }
  }

  /**
   * Single `bazel cquery --output=starlark` call that returns output file paths
   * grouped by label.
   */
  def runBazelCqueryOutputsByLabel(
      labels: Set[String],
      queryEnv: Env
  )(implicit ec: ExecutionContext): Future[Map[String, List[String]]] = {
    // Emit one tab-separated line per target: "//pkg:name\tpath1\tpath2..."
    val starlarkExpr =
      """str(target.label) + "\t" + "\t".join([f.path for f in target.files.to_list()])"""
    BazelQuery(
      s"set(${labels.mkString(" ")})",
      Starlark,
      extraArgs = List(s"--starlark:expr=$starlarkExpr"),
      queryType = CQuery
    ).run(queryEnv).map { output =>
      val result = output.linesIterator
        .map(_.trim)
        .filter(_.nonEmpty)
        .flatMap { line =>
          line.split("\t") match {
            case Array(rawLabel, paths @ _*) =>
              val label =
                if (rawLabel.startsWith("@@//")) rawLabel.substring(2) else rawLabel
              val normalized = paths.flatMap(normalizeBazelOutputPath).toList
              Some(label -> normalized)
            case _ =>
              scribe.warn(s"bazel-mbt: unexpected cquery starlark line: $line")
              None
          }
        }
        .toMap
      result
    }.recover { case e =>
      scribe.warn(s"bazel-mbt: cquery starlark query failed", e)
      Map.empty
    }
  }

  /** Converts `bazel-out/<config>/bin/<rest>` to `bazel-bin/<rest>`. */
  private def normalizeBazelOutputPath(path: String): Option[String] = {
    val binMarker = "/bin/"
    val idx = path.indexOf(binMarker)
    if (idx < 0) None
    else Some("bazel-bin/" + path.substring(idx + binMarker.length))
  }
}

  

case class BazelQuery(
    query: String,
    outputMode: BazelQuery.OutputMode,
    extraArgs: List[String] = Nil,
    queryType: BazelQuery.QueryType = BazelQuery.QueryType.Query
) {
  import BazelQuery.Env

  def run(
      env: Env
  )(implicit ec: ExecutionContext): Future[String] = {
    import env._
    val buf = new StringBuilder()
    shellRunner
      .run(
        s"bazel-mbt-$queryType",
        List(
          "bazel",
          queryType.toString,
          query,
          s"--output=$outputMode",
          "--keep_going",
        ) ++ extraArgs,
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
