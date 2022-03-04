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
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.core.Types.ErrorType
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
import dotty.tools.dotc.core.Types.PolyType
import dotty.tools.dotc.core.Types.Type
import dotty.tools.dotc.core.Types.MethodType
import scala.annotation.tailrec
import scala.collection.immutable.LazyList.cons
import dotty.tools.dotc.core.Flags

object SignatureHelpProvider:

  def contribute(driver: InteractiveDriver, params: OffsetParams) =
    val uri = params.uri
    val sourceFile = CompilerInterfaces.toSource(params.uri, params.text)
    driver.run(uri, sourceFile)

    given ctx: Context = driver.currentCtx
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
    Signatures.callInfo(path, span)(using ctx) match
      case default @ (_, _, Nil) =>
        path match
          case (tpd.UnApply(fun, _, patterns)) :: parent :: _ =>
            val implicitsBefore = countParams(fun)

            def defaultParamIndex =
              patterns.indexWhere(_.span.contains(span)) match
                case -1 => (patterns.length - 1 max 0) + implicitsBefore
                case n => n + implicitsBefore

            val funSymbol = fun.symbol
            val retType = funSymbol.info.finalResultType
            val isSomeMatch = funSymbol.owner.companionClass == defn.SomeClass
            val isExplicitUnapply =
              !isSomeMatch && retType.typeSymbol == defn.OptionClass
            val paramIndex =
              if isExplicitUnapply then implicitsBefore
              else defaultParamIndex

            val signatureSymbol =
              if isSomeMatch then
                funSymbol.owner.companionClass.primaryConstructor
              else if isExplicitUnapply then funSymbol
              else
                // if unapply doesn't return option it means it's a case class
                retType.typeSymbol.primaryConstructor
            val alternatives =
              signatureSymbol.owner.info
                .member(signatureSymbol.name)
                .alternatives
            val alternativeIndex =
              alternatives.map(_.symbol).indexOf(signatureSymbol) max 0
            (paramIndex, alternativeIndex, alternatives)
          case _ =>
            default
      case other =>
        other

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

  /**
   * ****** Copied over from the compiler needed for versions prior to 3.1.3 ************
   */
  /**
   * The number of parameters that are applied in `tree`.
   *
   * This handles currying, so for an application such as `foo(1, 2)(3)`, the result of
   * `countParams` should be 3.
   *
   * @param tree The tree to inspect.
   * @return The number of parameters that are passed.
   */
  private def countParams(tree: tpd.Tree): Int =
    tree match
      case tpd.Apply(fun, params) => countParams(fun) + params.length
      case _ => 0

end SignatureHelpProvider
