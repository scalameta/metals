package scala.meta.internal.pc

import scala.collection.JavaConverters.*

import scala.meta.internal.mtags.MtagsEnrichments.*
import scala.meta.pc.OffsetParams

import dotty.tools.dotc.ast.Trees.UnApply
import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.ast.tpd.Tree
import dotty.tools.dotc.core.Constants.Constant
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Denotations.SingleDenotation
import dotty.tools.dotc.core.Symbols
import dotty.tools.dotc.core.Types.ErrorType
import dotty.tools.dotc.core.Types.MethodType
import dotty.tools.dotc.interactive.Interactive
import dotty.tools.dotc.interactive.InteractiveDriver
import dotty.tools.dotc.reporting.AmbiguousOverload
import dotty.tools.dotc.reporting.NoMatchingOverload
import dotty.tools.dotc.util.Signatures
import dotty.tools.dotc.util.SourcePosition
import dotty.tools.dotc.util.Spans.Span
import org.eclipse.lsp4j.ParameterInformation
import org.eclipse.lsp4j.SignatureHelp
import org.eclipse.lsp4j.SignatureInformation

object SignatureHelpProvider:

  def contribute(driver: InteractiveDriver, params: OffsetParams) =
    val uri = params.uri
    val sourceFile = CompilerInterfaces.toSource(params.uri, params.text)
    driver.run(uri, sourceFile)

    given ctx: Context = driver.currentCtx
    // given locatedCtx: Context = driver.localContext(params)
    val pos = driver.sourcePosition(params)
    val trees = driver.openedTrees(uri)
    val path =
      Interactive
        .pathTo(trees, pos)(using ctx)
        .dropWhile {
          case _: tpd.Apply => false
          case unapply: tpd.UnApply =>
            // Don't show tuple unapply method
            val sym = unapply.fun.symbol.owner.companionClass
            Symbols.defn.isTupleClass(sym)
          case _ => true
        }

    val (paramN, callableN, alternatives) =
      callInfo(path, pos.span)(using ctx)

    val signatureInfos =
      alternatives.flatMap(Signatures.toSignature(_)(using ctx))
    new SignatureHelp(
      signatureInfos.map(signatureToSignatureInformation).asJava,
      callableN,
      paramN
    )
  end contribute

  private def callInfo(path: List[Tree], span: Span)(using
      ctx: Context
  ): (Int, Int, List[SingleDenotation]) =
    path match
      case (tpd.UnApply(fun, implicits, patterns)) :: _ =>
        val paramIndex = patterns.indexWhere(_.span.contains(span)) match
          case -1 => (patterns.length - 1 max 0)
          case n => n

        val (alternativeIndex, alternatives) = fun.tpe match
          case err: ErrorType =>
            val (alternativeIndex, alternatives) =
              alternativesFromError(err, patterns)
            (alternativeIndex, alternatives)

          case _ =>
            val funSymbol = fun.symbol
            val alternatives =
              funSymbol.owner.info.member(funSymbol.name).alternatives
            val alternativeIndex =
              alternatives.map(_.symbol).indexOf(funSymbol) max 0
            (alternativeIndex, alternatives)
        (paramIndex, alternativeIndex, alternatives)
      case _ =>
        Signatures.callInfo(path, span)(using ctx)

  /**
   * Copied over from Signatures since it's a private method.
   */
  private def alternativesFromError(err: ErrorType, params: List[tpd.Tree])(
      using Context
  ): (Int, List[SingleDenotation]) =
    val alternatives =
      err.msg match
        case msg: AmbiguousOverload => msg.alternatives
        case msg: NoMatchingOverload => msg.alternatives
        case _ => Nil

    // If the user writes `foo(bar, <cursor>)`, the typer will insert a synthetic
    // `null` parameter: `foo(bar, null)`. This may influence what's the "best"
    // alternative, so we discard it.
    val userParams = params match
      case xs :+ (nul @ tpd.Literal(Constant(null))) if nul.span.isZeroExtent =>
        xs
      case _ => params
    val userParamsTypes = userParams.map(_.tpe)

    // Assign a score to each alternative (how many parameters are correct so far), and
    // use that to determine what is the current active signature.
    val alternativesScores = alternatives.map { alt =>
      alt.info.stripPoly match
        case tpe: MethodType =>
          userParamsTypes
            .zip(tpe.paramInfos)
            .takeWhile { case (t0, t1) => t0 <:< t1 }
            .size
        case _ =>
          0
    }
    val bestAlternative =
      if alternativesScores.isEmpty then 0
      else alternativesScores.zipWithIndex.maxBy(_._1)._2

    (bestAlternative, alternatives)
  end alternativesFromError

  /**
   * Convert `param` to `ParameterInformation`
   */
  private def paramToParameterInformation(
      param: Signatures.Param
  ): ParameterInformation =
    val documentation = param.doc.map(_.toMarkupContent)
    val info = new ParameterInformation(param.show)
    documentation.foreach(info.setDocumentation(_))
    info

  private def signatureToSignatureInformation(
      signature: Signatures.Signature
  )(using ctx: Context): SignatureInformation =
    val paramInfoss =
      signature.paramss.map(_.map(paramToParameterInformation))
    val paramLists = signature.paramss
      .map { paramList =>
        val labels = paramList.map(_.show)
        val prefix = if paramList.exists(_.isImplicit) then "implicit " else ""
        labels.mkString(prefix, ", ", "")
      }
      .mkString("(", ")(", ")")
    val tparamsLabel =
      if signature.tparams.isEmpty then ""
      else signature.tparams.mkString("[", ", ", "]")
    val returnTypeLabel = signature.returnType.map(t => s": $t").getOrElse("")
    val label = s"${signature.name}$tparamsLabel$paramLists$returnTypeLabel"
    val documentation = signature.doc.map(_.toMarkupContent)
    val sig = new SignatureInformation(label)
    sig.setParameters(paramInfoss.flatten.asJava)
    documentation.foreach(sig.setDocumentation(_))
    sig
  end signatureToSignatureInformation
end SignatureHelpProvider
