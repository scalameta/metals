package scala.meta.internal.metals.mcp

import java.net.URI

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Random

import scala.meta.internal.metals.CompilerOffsetParams
import scala.meta.internal.metals.Compilers
import scala.meta.internal.metals.EmptyCancelToken
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.semanticdb.Scala._

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemTag

/**
 * Inspects a symbol by calling completions and signature help on the presentation compiler.
 *
 * To do this, it creates a synthetic code snippet, see [[makeCompilerOffsetParams]].
 * For some symbols, synthetic values are created,
 * in that case auxiliary context with the values is added to the result
 * interpratation of path-dependent types.
 *
 * E.g.:,
 *
 * for completions:
 * ````
 * // `com.pkg.Bar$`
 * object mcp1234 {
 *   com.pkg.Bar.@@
 * }
 * // `com.pkg.Bar#Foo#`
 * object mcp1234 {
 *   val mcp0 = ???.asInstanceOf[com.pkg.Bar]
 *   ???.asInstanceOf[mcp0.Foo].@@
 * }
 * ```
 *
 * for signatures:
 * ```
 * // `com.pkg.Foo#Bar$foo().`
 * object mcp1234 {
 *   ???.asInstanceOf[com.pkg.Foo#Bar.type].foo(@@)
 * }
 * // `com.pkg.Foo#`
 * object mcp1234 {
 *   new com.pkg.Foo(@@)
 * }
 * // `com.pkg.Foo#Bar.Baz#`
 * object mcp1234{
 *   val mcp0 = ???.asInstanceOf[com.pkg.Foo]
 *   new mcp0.Bar.Baz(@@)
 * }
 * ```
 */
private class McpInspectProvider(
    compilers: Compilers,
    symbol: SymbolSearchResult,
    buildTarget: BuildTargetIdentifier,
)(implicit ec: ExecutionContext) {
  private val id = Random.nextLong().abs.toLong
  private val desc = symbol.symbol.desc

  private lazy val partsForSyntheticDefs =
    symbol.symbol.split('#').dropRight(1).map(_.fqcn)
  private lazy val partForCompletion =
    symbol.symbol.split('#').lastOption.getOrElse(symbol.symbol).fqcn

  private def inspect(): Future[Option[SymbolInspectResult]] = {
    for {
      completions <- getCompletions()
      signatures <- getSignatures()
    } yield {
      symbol.symbolType match {
        case SymbolType.Package =>
          Some(PackageInspectResult(symbol.path, completions))
        case SymbolType.Class =>
          Some(
            ClassInspectResult(
              symbol.path,
              completions,
              signatures,
              auxilaryContext(),
            )
          )
        case SymbolType.Object =>
          Some(ObjectInspectResult(symbol.path, completions, auxilaryContext()))
        case SymbolType.Trait =>
          Some(TraitInspectResult(symbol.path, completions))
        case SymbolType.Method | SymbolType.Function | SymbolType.Constructor =>
          Some(MethodInspectResult(symbol.path, signatures, symbol.symbolType))
        case _ => None
      }
    }
  }

  private def auxilaryContext(): String = {
    if (partsForSyntheticDefs.isEmpty) ""
    else {
      val printedValues = partsForSyntheticDefs.zipWithIndex
        .map { case (tpe, index) =>
          s"${mcpDef(index)}: ${buildPathDependentType(index, tpe)}"
        }
        .map(" - " + _)
        .mkString("\n")
      s"""|Given synthetic values for path-dependent types:
          |$printedValues
          |""".stripMargin
    }
  }

  private def getCompletions() = {
    def isInteresting(completion: CompletionItem): Boolean = {
      !McpQueryEngine.uninterestingCompletions(completion.getLabel())
    }

    def isDeprecated(completion: CompletionItem): Boolean = {
      Option(completion.getTags.asScala)
        .getOrElse(Nil)
        .contains(CompletionItemTag.Deprecated)
    }

    if (desc.isType || desc.isTerm || desc.isPackage) {
      compilers
        .completions(
          buildTarget,
          makeCompilerOffsetParams(forSignature = false),
        )
        .map { completionList =>
          completionList
            .getItems()
            .asScala
            .collect {
              case completion
                  if isInteresting(completion) && !isDeprecated(completion) =>
                completion.getLabel()
            }
            .toList
        }
    } else Future.successful(Nil)
  }

  private def getSignatures(): Future[List[String]] = {
    if (desc.isMethod || desc.isType) {
      compilers
        .signatureHelp(
          buildTarget,
          makeCompilerOffsetParams(forSignature = true),
        )
        .map {
          _.getSignatures().asScala.map(_.getLabel()).toList
        }
    } else Future.successful(Nil)
  }

  private def makeCompilerOffsetParams(forSignature: Boolean) = {
    val isType = symbol.symbol.desc.isType
    val completionTpe =
      buildPathDependentType(partsForSyntheticDefs.length, partForCompletion)
    val completionOrSignature =
      if (forSignature)
        if (isType) s"new $completionTpe()" else s"$completionTpe()"
      else if (isType) s"???.asInstanceOf[$completionTpe]."
      else s"$completionTpe."

    val definitions = partsForSyntheticDefs.zipWithIndex.map {
      case (tpe, index) =>
        s"val ${mcpDef(index)} = ???.asInstanceOf[${buildPathDependentType(index, tpe)}]"
    }
    val code = (definitions :+ completionOrSignature)
      .map("  " + _)
      .mkString("\n", "\n", "\n")
    val withWrapper = s"object mcp$id {$code}"

    CompilerOffsetParams(
      URI.create(s"mcp$id.scala"),
      withWrapper,
      // calculating offet
      // for signature help we skip last 3 characters `)\n}`
      // for completions we skip last 2 characters `\n}`
      withWrapper.length() - (if (forSignature) 3 else 2),
      EmptyCancelToken,
    )
  }

  private def mcpDef(index: Int) = s"mcp$index"

  private def buildPathDependentType(index: Int, tpe: String) = {
    val prefix = if (index == 0) "" else s"mcp${index - 1}."
    s"$prefix$tpe"
  }
}

object McpInspectProvider {
  def inspect(
      compilers: Compilers,
      symbol: SymbolSearchResult,
      buildTarget: BuildTargetIdentifier,
  )(implicit ec: ExecutionContext): Future[Option[SymbolInspectResult]] = {
    new McpInspectProvider(compilers, symbol, buildTarget).inspect()
  }
}
