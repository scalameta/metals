package scala.meta.internal.metals.mbt.importer

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Try

import scala.meta.internal.builds.BazelProjectViewTargets
import scala.meta.internal.builds.ShellRunner
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.process.ExitCodes
import scala.meta.internal.process.ProcessOutput
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

    private val targetStreamArgs = List(
      "--proto:flatten_selects=false",
      "--proto:output_rule_attrs=srcs,scalacopts,javacopts,scala_version,jars,srcjar",
    )

    // Binary length-delimited `Target` stream; must be captured as raw bytes.
    case object StreamedProto
        extends OutputMode("streamed_proto", targetStreamArgs)
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

  def quoteTarget(target: String): Option[String] =
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
    BazelQuery(query, outputMode = StreamedProto)
  }

  def allScalaLibrariesQuery: BazelQuery =
    BazelQuery("filter('scala.library', deps(//...))", outputMode = Label)

  private val ruleKinds: List[String] =
    List(
      "scala_library", "java_library", "scala_binary", "java_binary",
      "scala_test", "java_test", "scala_import", "java_import",
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
    val buf = new StringBuilder()
    execute(
      env,
      ProcessOutput.Lines { line =>
        buf.append(line)
        buf.append(System.lineSeparator())
      },
    )(() => buf.toString)
  }

  def runProtoDump(
      env: Env
  )(implicit ec: ExecutionContext): Future[BazelTargetsProtoDump] =
    runRaw(env)
      .map(BazelStreamedProto.parseRules)
      .map(new BazelTargetsProtoDump(_))

  private def runRaw(
      env: Env
  )(implicit ec: ExecutionContext): Future[Array[Byte]] = {
    val buf = new ByteArrayOutputStream()
    execute(env, ProcessOutput.RawBytes(buf))(() => buf.toByteArray)
  }

  private def execute[A](
      env: Env,
      processOut: ProcessOutput,
  )(result: () => A)(implicit ec: ExecutionContext): Future[A] = {
    import env._
    val (queryArgs, queryFile) = prepareQueryArgs(query)
    shellRunner
      .run(
        "bazel-mbt-query",
        bazelQueryArgs(queryArgs),
        projectRoot,
        redirectErrorOutput = false,
        javaHome,
        processOut = processOut,
        processErr = scribe.warn(_),
      )
      .future
      // Ignore a failed delete; the VM-exit hook retries it.
      .andThen { case _ => Try(queryFile.foreach(Files.delete)) }
      .flatMap {
        case ExitCodes.Cancel =>
          Future.failed(
            new java.util.concurrent.CancellationException(
              "bazel-mbt: query cancelled"
            )
          )
        case code =>
          if (code != ExitCodes.Success)
            scribe.warn(
              s"bazel-mbt: bazel query failed with exit code $code, but might be unrelated."
            )
          Future.successful(result())
      }
  }

  private def bazelQueryArgs(queryArgs: List[String]): List[String] =
    List(
      "bazel",
      "query",
      s"--output=$outputMode",
    ) ++ outputMode.extraArgs ++ List("--keep_going") ++ queryArgs

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
