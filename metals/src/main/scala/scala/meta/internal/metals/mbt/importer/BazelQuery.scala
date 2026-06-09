package scala.meta.internal.metals.mbt.importer

import java.nio.file.Files
import java.nio.file.Path

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Try

import scala.meta.internal.builds.BazelProjectViewTargets
import scala.meta.internal.builds.ShellRunner
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.process.ExitCodes
import scala.meta.io.AbsolutePath

object BazelQuery {

  /**
   * If the query is longer than this value, write it to a temporary file
   * and use `--query_file` when invoking Bazel.
   * This is necessary to run very long queries on Windows.
   * @see https://learn.microsoft.com/en-us/troubleshoot/windows-client/shell-experience/command-line-string-limitation
   */
  val queryStringMaxLength: Int = 4000

  case class Env(
      projectRoot: AbsolutePath,
      shellRunner: ShellRunner,
      javaHome: Option[String],
  )

  sealed abstract class OutputMode(name: String, val extraArgs: List[String]) {
    override def toString(): String = name
  }
  object OutputMode {
    case object Label extends OutputMode("label", Nil)
    case object Xml extends OutputMode("xml", Nil)

    /**
     * `--output=streamed_jsonproto` prints one `Target` protocol buffer per line
     * as JSON. With `--proto:flatten_selects=false` it preserves each `srcs`
     * `select()` (every branch keyed by its `config_setting` label), unlike
     * `xml`/`label`, which flatten every branch into one list. This lets us see
     * which source files belong to which `scala_version` `config_setting` branch
     * without configuring (analyzing) the build. `streamed_jsonproto` (rather
     * than `jsonproto`) emits newline-delimited JSON, sidestepping the
     * invalid-JSON-for-multiple-targets bug in `jsonproto`.
     */
    case object StreamedJsonProto
        extends OutputMode(
          "streamed_jsonproto",
          List("--proto:flatten_selects=false"),
        )
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

  private val reservedKeywords: Set[String] =
    Set("except", "in", "intersect", "let", "set", "union")

  private val unquotedWordChars: Set[Char] =
    (('a' to 'z') ++ ('A' to 'Z') ++ ('0' to '9')).toSet ++
      Set('*', '/', '@', '.', '-', '_', ':', '$', '~', '[', ']')

  private def needsQuoting(target: String): Boolean =
    reservedKeywords.contains(target) || target.headOption.exists {
      case '-' | '*' => true
      case _ =>
        val allowPlus = target.startsWith("@@")
        !target.forall { c =>
          unquotedWordChars.contains(c) || (c == '+' && allowPlus)
        }
    }

  private def quoteTarget(target: String): Option[String] =
    if (needsQuoting(target)) {
      val hasDouble = target.contains('"')
      val hasSingle = target.contains('\'')
      if (hasDouble && hasSingle) {
        scribe.warn(
          s"bazel-mbt: skipping target '$target' because it contains both single and double quotes, " +
            "which cannot be represented in Bazel query language"
        )
        None
      } else if (hasDouble)
        Some(s"'$target'")
      else
        Some(s""""$target"""")
    } else Some(target)

  def fullInformationQuery(targets: List[String]): BazelQuery = {
    val escaped = targets.flatMap(quoteTarget)
    val query = s"deps(set(${escaped.mkString(" ")}))"
    BazelQuery(query, outputMode = Xml)
  }

  /**
   * Queries the targets as `streamed_jsonproto` so each `srcs` `select()` is
   * preserved. Used to map version-specific source files (e.g. the Scala 3
   * branch of a `select_for_scala_version` target) to their Scala version,
   * which the flattened `xml` output of [[fullInformationQuery]] cannot express.
   */
  def selectAwareSrcsQuery(targets: List[String]): BazelQuery = {
    val escaped = targets.flatMap(quoteTarget)
    val query = s"set(${escaped.mkString(" ")})"
    BazelQuery(query, outputMode = StreamedJsonProto)
  }

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
  import BazelQuery._

  def run(
      env: Env
  )(implicit ec: ExecutionContext): Future[String] = {
    import env._
    val buf = new StringBuilder()
    val (queryArgs, queryFile) = prepareQueryArgs(query)
    shellRunner
      .run(
        "bazel-mbt-query",
        List(
          "bazel",
          "query",
          s"--output=$outputMode",
        ) ++ outputMode.extraArgs ++ List("--keep_going") ++ queryArgs,
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
      .andThen {
        case result => {
          Try {
            // Just ignore the possible error; we will have a second attempt to delete the file upon the VM exit
            queryFile.foreach(Files.delete)
          }
          result
        }
      }
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

  private def prepareQueryArgs(query: String): (List[String], Option[Path]) = {
    if (query.length() <= queryStringMaxLength) (List(query), None)
    else {
      val queryFile = Files.createTempFile("metals-bazel-query-", ".txt")
      queryFile.toFile().deleteOnExit()
      scribe.debug(
        s"Bazel query exceeds $queryStringMaxLength characters, writing it to $queryFile"
      )
      Files.createDirectories(queryFile.getParent)
      queryFile.writeText(query)
      (List("--query_file", queryFile.toString()), Some(queryFile))
    }
  }

}
