package scala.meta.internal.parsing

import scala.collection.concurrent.TrieMap

import scala.meta._
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.ScalaVersionSelector
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.parsers.Parsed

import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity

/**
 * Manages parsing of Scala source files into Scalameta syntax trees.
 *
 * - provides the latest good Scalameta tree for a given source file
 *   similar as `Buffers` provides the current text content.
 * - produces diagnostics for syntax errors.
 */
final class Trees(
    buildTargets: BuildTargets,
    buffers: Buffers,
    scalaVersionSelector: ScalaVersionSelector
) {

  private val trees = TrieMap.empty[AbsolutePath, Tree]

  def get(path: AbsolutePath): Option[Tree] =
    trees.get(path).orElse {
      // Fallback to parse without caching result.
      parse(path, getDialect(path)).flatMap(_.toOption)
    }

  def didClose(fileUri: AbsolutePath): Unit = {
    trees.remove(fileUri)
  }

  /**
   * Parse file at the given path and return a list of errors if there are any and
   * the file belongs to a Scala 2 target.
   *
   * @param path file to parse
   * @return list of errors if the file failed to parse
   */
  def didChange(path: AbsolutePath): List[Diagnostic] = {
    val dialect = getDialect(path)
    parse(path, dialect) match {
      // only publish parser diagnostics for Scala 2, which does not support significant indentation
      case Some(parsed) if !dialect.allowSignificantIndentation =>
        parsed match {
          case Parsed.Error(pos, message, _) =>
            List(
              new Diagnostic(
                pos.toLSP,
                message,
                DiagnosticSeverity.Error,
                "scalameta"
              )
            )
          case Parsed.Success(tree) =>
            trees(path) = tree
            List()
        }
      // don't publish diagnostics
      case _ =>
        List()
    }
  }

  private def parse(
      path: AbsolutePath,
      dialect: Dialect
  ): Option[Parsed[Source]] = {
    for {
      text <- buffers.get(path).orElse(path.readTextOpt)
    } yield {
      val input = Input.VirtualFile(path.toString(), text)
      dialect(input).parse[Source]
    }
  }

  def getDialect(path: AbsolutePath): Dialect = {

    def dialectFromBuildTarget = buildTargets
      .inverseSources(path)
      .flatMap(id => buildTargets.scalaTarget(id))
      .map(_.dialect)

    Option(path.extension) match {
      case Some("scala") =>
        dialectFromBuildTarget.getOrElse(
          scalaVersionSelector.fallbackDialect(isAmmonite = false)
        )
      case Some("sbt") => dialects.Sbt
      case Some("sc") =>
        // worksheets support Scala 3, but ammonite scripts do not
        val dialect = dialectFromBuildTarget.getOrElse(
          scalaVersionSelector.fallbackDialect(isAmmonite =
            path.isAmmoniteScript
          )
        )
        dialect
          .copy(allowToplevelTerms = true, toplevelSeparator = "")
      case _ => scalaVersionSelector.fallbackDialect(isAmmonite = false)
    }
  }

}

object Trees {

  /* Tokenizing works perfectly fine with 212 dialect as long as we are only
   * interested in having stable results. This is not the case for parsing.
   */
  val defaultTokenizerDialect: Dialect = scala.meta.dialects.Scala213

}
