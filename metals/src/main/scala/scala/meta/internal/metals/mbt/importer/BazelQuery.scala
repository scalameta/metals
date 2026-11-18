package scala.meta.internal.metals.mbt.importer

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Try

import scala.meta.internal.builds.BazelProjectViewTargets
import scala.meta.internal.builds.ShellRunner
import scala.meta.internal.metals.MetalsEnrichments.*
import scala.meta.internal.process.ExitCodes
import scala.meta.internal.process.ProcessOutput
import scala.meta.io.AbsolutePath

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

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

  sealed abstract class OutputMode(name: String) {
    override def toString(): String = name
  }
  object OutputMode {
    case object Label extends OutputMode("label")
    case object Xml extends OutputMode("xml")
    case object StreamedJsonproto extends OutputMode("streamed_jsonproto")

    // valid only for "bazel cquery"
    case object Starlark extends OutputMode("starlark")
  }
  sealed abstract class QueryType(name: String) {
    override def toString(): String = name
  }
  object QueryType {
    case object Query extends QueryType("query")
    case object CQuery extends QueryType("cquery")
  }

  import OutputMode.*

  /**
   * Exit code returned by `bazel query --keep_going` when some packages fail
   * to load or analyze while valid partial query results are still emitted.
   */
  private val PartialAnalysisFailureExitCode: Int = 3

  private val MaxCapturedStderrLines: Int = 20

  def buildRuleKindsQuery(patterns: List[String]): BazelQuery = {
    val trimmed = for {
      pattern <- patterns
      p = pattern.trim
      if p.nonEmpty
    } yield p
    val (rawExcludes, rawIncludes) = trimmed.partition(_.startsWith("-"))
    val parsedIncludes = rawIncludes.flatMap(quoteTarget)
    val includes =
      if (parsedIncludes.isEmpty) BazelProjectViewTargets.defaultPatterns
      else parsedIncludes
    val excludes = for {
      raw <- rawExcludes
      stripped = raw.stripPrefix("-").trim
      if stripped.nonEmpty
      quoted <- quoteTarget(stripped)
    } yield quoted
    val targetExpr =
      if (excludes.isEmpty) s"set(${includes.mkString(" ")})"
      else
        s"set(${includes.mkString(" ")}) except set(${excludes.mkString(" ")})"
    val query = s"kind('${ruleKinds.mkString("|")}', $targetExpr)"
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
    BazelQuery(query, outputMode = Xml)
  }

  private val ruleKinds: List[String] =
    List(
      "scala_library", "java_library", "scala_binary", "java_binary",
      "scala_test", "java_test", "scala_import", "java_import",
    )

  private val defaultConditionLabel: String = "//conditions:default"

  def unflattenedRuleAttrsQuery(
      targets: List[String],
      attributeNames: List[String],
  ): Option[BazelQuery] = {
    val escaped = targets.flatMap(quoteTarget)
    Option.when(escaped.nonEmpty && attributeNames.nonEmpty)(
      BazelQuery(
        s"set(${escaped.mkString(" ")})",
        outputMode = StreamedJsonproto,
        extraArgs = List(
          "--proto:flatten_selects=false",
          s"--proto:output_rule_attrs=${attributeNames.mkString(",")}",
          "--noproto:rule_inputs_and_outputs",
          "--noproto:locations",
        ),
      )
    )
  }

  def parseUnflattenedStringLists(
      jsonproto: String,
      attributeNames: Set[String],
  ): Map[String, Map[String, List[String]]] = {
    val parsedRules = for {
      rawLine <- jsonproto.split('\n')
      line = rawLine.trim
      if line.startsWith("{")
      root <- Try(JsonParser.parseString(line)).toOption
      if root.isJsonObject
      rule <- jsonObject(root.getAsJsonObject, "rule")
      target <- jsonString(rule, "name")
      if target.nonEmpty
    } yield {
      val attrsByName = (for {
        attrElem <- jsonArray(rule, "attribute")
        if attrElem.isJsonObject
        attr = attrElem.getAsJsonObject
        name <- jsonString(attr, "name")
        if attributeNames.contains(name)
      } yield name -> extractStringListAttribute(attr)).toMap
      target -> attrsByName
    }
    if (parsedRules.isEmpty) Map.empty
    else
      attributeNames.map { attrName =>
        val byTarget = parsedRules.map { case (target, attrsByName) =>
          target -> attrsByName.getOrElse(attrName, Nil)
        }.toMap
        attrName -> byTarget
      }.toMap
  }

  private def extractStringListAttribute(attr: JsonObject): List[String] =
    jsonString(attr, "type") match {
      case Some("STRING_LIST") =>
        stringListValues(attr)
      case Some("SELECTOR_LIST") =>
        val values = for {
          selectorList <- jsonObject(attr, "selectorList").toSeq
          elem <- jsonArray(selectorList, "elements")
          if elem.isJsonObject
          entry <- jsonArray(elem.getAsJsonObject, "entries")
          if entry.isJsonObject
          entryObj = entry.getAsJsonObject
          if jsonString(entryObj, "label").exists(isDefaultCondition)
          value <- stringListValues(entryObj)
        } yield value
        values.toList
      case _ =>
        Nil
    }

  private def isDefaultCondition(label: String): Boolean =
    label.stripPrefix("@@").stripPrefix("@") == defaultConditionLabel

  private def stringListValues(obj: JsonObject): List[String] = {
    val values = for {
      elem <- jsonArray(obj, "stringListValue")
      if elem.isJsonPrimitive
    } yield elem.getAsString
    values.toList
  }

  private def jsonObject(obj: JsonObject, key: String): Option[JsonObject] =
    Option(obj.get(key)).filter(_.isJsonObject).map(_.getAsJsonObject)

  private def jsonArray(obj: JsonObject, key: String): Iterable[JsonElement] =
    Option(obj.get(key))
      .filter(_.isJsonArray)
      .map(_.getAsJsonArray.asScala)
      .getOrElse(Nil)

  private def jsonString(obj: JsonObject, key: String): Option[String] =
    Option(obj.get(key)).filter(_.isJsonPrimitive).map(_.getAsString)

}

case class BazelQuery(
    query: String,
    outputMode: BazelQuery.OutputMode,
    extraArgs: List[String] = Nil,
    queryType: BazelQuery.QueryType = BazelQuery.QueryType.Query,
) {
  import BazelQuery.*

  def run(
      env: Env,
      mbtJavaHome: Option[String] = None,
  )(implicit ec: ExecutionContext): Future[String] = {
    import env.*
    val buf = new StringBuilder()
    val errLines = mutable.Queue.empty[String]
    val (queryArgs, queryFile) = prepareQueryArgs(query)
    shellRunner
      .run(
        s"bazel-mbt-$queryType",
        List(
          "bazel",
          queryType.toString,
          s"--output=$outputMode",
          "--keep_going",
        ) ++ queryArgs ++ extraArgs,
        projectRoot,
        redirectErrorOutput = false,
        mbtJavaHome.orElse(javaHome),
        processOut = ProcessOutput.Lines { line =>
          buf.append(line)
          buf.append(System.lineSeparator())
        },
        processErr = { line =>
          scribe.warn(line)
          val trimmed = line.trim
          if (trimmed.nonEmpty) {
            errLines.synchronized {
              if (errLines.size >= MaxCapturedStderrLines) {
                errLines.dequeue()
              }
              errLines.enqueue(trimmed)
            }
          }
        },
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
        case PartialAnalysisFailureExitCode =>
          scribe.warn(
            s"bazel-mbt: bazel $queryType exited with code $PartialAnalysisFailureExitCode (partial results with --keep_going)."
          )
          Future.successful(buf.toString)
        case ExitCodes.Cancel =>
          Future.failed(
            new CancellationException(
              "bazel-mbt: query cancelled"
            )
          )
        case code =>
          val details = errLines.synchronized {
            errLines.mkString("; ")
          }
          val suffix = if (details.nonEmpty) s": $details" else ""
          Future.failed(
            new RuntimeException(
              s"bazel-mbt: bazel $queryType failed with exit code $code$suffix"
            )
          )
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
