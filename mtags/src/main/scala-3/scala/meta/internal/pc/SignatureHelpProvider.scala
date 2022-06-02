package scala.meta.internal.pc

import scala.collection.JavaConverters.*
import scala.util.control.NonFatal

import scala.meta.internal.mtags.BuildInfo
import scala.meta.internal.mtags.MtagsEnrichments.*
import scala.meta.internal.semver.SemVer
import scala.meta.pc.OffsetParams
import scala.meta.pc.SymbolDocumentation
import scala.meta.pc.SymbolSearch

import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Flags
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.interactive.Interactive
import dotty.tools.dotc.interactive.InteractiveDriver
import dotty.tools.dotc.util.Signatures
import dotty.tools.dotc.util.Signatures.Signature
import dotty.tools.dotc.util.SourcePosition
import org.eclipse.{lsp4j as l}

object SignatureHelpProvider:

  def signatureHelp(
      driver: InteractiveDriver,
      params: OffsetParams,
      search: SymbolSearch
  ) =
    val uri = params.uri
    val sourceFile = CompilerInterfaces.toSource(params.uri, params.text)
    driver.run(uri, sourceFile)

    given ctx: Context = driver.currentCtx

    val pos = driver.sourcePosition(params)
    val trees = driver.openedTrees(uri)

    val path =
      Interactive.pathTo(trees, pos).dropWhile(t => notCurrentApply(t, pos))

    val (paramN, callableN, alternativeSignatures) =
      MetalsSignatures.signatures(
        search,
        path,
        pos
      )

    val signatureInfos = alternativeSignatures.map { case (signature, denot) =>
      search.symbolDocumentation(denot.symbol) match
        case Some(doc) =>
          withDocumentation(
            doc,
            signature,
            denot.symbol.is(Flags.JavaDefined)
          ).getOrElse(signature)
        case _ => signature

    }

    new l.SignatureHelp(
      signatureInfos.map(signatureToSignatureInformation).asJava,
      callableN,
      paramN
    )
  end signatureHelp

  private def isTuple(tree: tpd.Tree)(using Context): Boolean =
    ctx.definitions.isTupleClass(tree.symbol.owner.companionClass)

  private def notCurrentApply(
      tree: tpd.Tree,
      pos: SourcePosition
  )(using Context): Boolean =
    tree match
      case unapply: tpd.UnApply =>
        unapply.fun.span.contains(pos.span) || isTuple(unapply)
      case appl: tpd.GenericApply =>
        /* find first apply that the cursor is located in arguments and not at function name
         * for example in:
         *   `Option(1).fold(2)(@@_ + 1)`
         * we want to find the tree responsible for the entire location, not just `_ + 1`
         */
        appl.fun.span.contains(pos.span)

      case _ => true

  private def withDocumentation(
      info: SymbolDocumentation,
      signature: Signatures.Signature,
      isJavaSymbol: Boolean
  ): Option[Signature] =
    val allParams = info.parameters.asScala
    def updateParams(
        params: List[Signatures.Param],
        index: Int
    ): List[Signatures.Param] =
      params match
        case Nil => Nil
        case head :: tail =>
          val rest = updateParams(tail, index + 1)
          allParams.lift(index) match
            case Some(paramDoc) =>
              val newName =
                if isJavaSymbol && head.name.startsWith("x$") then
                  paramDoc.displayName
                else head.name
              head.copy(
                doc = Some(paramDoc.docstring),
                name = newName
              ) :: rest
            case _ => head :: rest

    def updateParamss(
        params: List[List[Signatures.Param]],
        index: Int
    ): List[List[Signatures.Param]] =
      params match
        case Nil => Nil
        case head :: tail =>
          val updated = updateParams(head, index)
          updated :: updateParamss(tail, index + head.size)
    val updatedParams = updateParamss(signature.paramss, 0)
    Some(signature.copy(doc = Some(info.docstring), paramss = updatedParams))
  end withDocumentation

  private def signatureToSignatureInformation(
      signature: Signatures.Signature
  ): l.SignatureInformation =
    val paramInfoss =
      signature.paramss.map(_.map(paramToParameterInformation))
    val paramLists = signature.paramss
      .map { paramList =>
        val labels = paramList.map(_.show)
        val prefix = if paramList.exists(_.isImplicit) then "using " else ""
        labels.mkString(prefix, ", ", "")
      }
      .mkString("(", ")(", ")")
    val tparamsLabel =
      if signature.tparams.isEmpty then ""
      else signature.tparams.mkString("[", ", ", "]")
    val returnTypeLabel = signature.returnType.map(t => s": $t").getOrElse("")
    val label = s"${signature.name}$tparamsLabel$paramLists$returnTypeLabel"
    val documentation = signature.doc.map(markupContent)
    val sig = new l.SignatureInformation(label)
    sig.setParameters(paramInfoss.flatten.asJava)
    documentation.foreach(sig.setDocumentation(_))
    sig
  end signatureToSignatureInformation

  /**
   * Convert `param` to `ParameterInformation`
   */
  private def paramToParameterInformation(
      param: Signatures.Param
  ): l.ParameterInformation =
    val documentation = param.doc.map(markupContent)
    val info = new l.ParameterInformation(param.show)
    documentation.foreach(info.setDocumentation(_))
    info

  private def markupContent(content: String): l.MarkupContent =
    if content.isEmpty then null
    else
      val markup = new l.MarkupContent
      markup.setKind("markdown")
      markup.setValue(content.trim)
      markup

end SignatureHelpProvider
