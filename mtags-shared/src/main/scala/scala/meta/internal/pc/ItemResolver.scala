package scala.meta.internal.pc

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.mtags.CommonMtagsEnrichments._
import scala.meta.pc.PresentationCompilerConfig
import scala.meta.pc.SymbolDocumentation

import org.eclipse.lsp4j.CompletionItem

trait ItemResolver {

  protected def adjustIndexOfJavaParams = 1

  protected def enrichDocs(
      item: CompletionItem,
      info: SymbolDocumentation,
      metalsConfig: PresentationCompilerConfig,
      docstring: => String,
      isJava: => Boolean
  ): CompletionItem = {
    if (isJava) {
      val data = item.data.getOrElse(CompletionItemData.empty)
      item.setLabel(replaceJavaParameters(info, item.getLabel))
      if (metalsConfig.isCompletionItemDetailEnabled) {
        item.setDetail(replaceJavaParameters(info, item.getDetail))
      }
      if (
        item.getTextEdit != null && (
          data.kind == CompletionItemData.OverrideKind ||
            data.kind == CompletionItemData.ImplementAllKind
        )
      ) {
        item.getTextEdit().asScala match {
          case Left(textEdit)
              if data.kind == CompletionItemData.ImplementAllKind =>
            val editText = textEdit.getNewText()

            val relatedEditOnly = editText.linesIterator
              .filter(
                _.contains(info.displayName() + "(")
              )
              .mkString
            val newText =
              replaceJavaParameters(info, relatedEditOnly)
            textEdit.setNewText(editText.replace(relatedEditOnly, newText))
          case Left(textEdit) =>
            val newText =
              replaceJavaParameters(info, textEdit.getNewText())
            textEdit.setNewText(newText)
          // Right[InsertReplaceEdit] is currently not used in Metals
          case _ =>
        }
        item.setLabel(replaceJavaParameters(info, item.getLabel))
      }
    } else {
      val defaults = info
        .parameters()
        .asScala
        .iterator
        .map(_.defaultValue())
        .filterNot(_.isEmpty)
        .toSeq
      item.setLabel(replaceScalaDefaultParams(item.getLabel, defaults))
      if (
        metalsConfig.isCompletionItemDetailEnabled && !item
          .getDetail()
          .isEmpty()
      ) {
        item.setDetail(
          replaceScalaDefaultParams(item.getDetail, defaults)
        )
      }
    }
    if (metalsConfig.isCompletionItemDocumentationEnabled) {
      item.setDocumentation(docstring.toMarkupContent())
    }
    item
  }

  // NOTE(olafur): it's hacky to use `String.replace("x$1", paramName)`, ideally we would use the
  // signature printer to pretty-print the signature from scratch with parameter names. However, that approach
  // is tricky because it would require us to JSON serialize/deserialize Scala compiler types. The reason
  // we don't print Java parameter names in `textDocument/completions` is because that requires parsing the
  // library dependency sources which is slow and `Thread.interrupt` cancellation triggers the `*-sources.jar`
  // to close causing other problems.
  protected def replaceJavaParameters(
      info: SymbolDocumentation,
      detail: String
  ): String = {
    info
      .parameters()
      .asScala
      .iterator
      .zipWithIndex
      .foldLeft(detail) { case (accum, (param, i)) =>
        accum.replace(
          s"x$$${i + adjustIndexOfJavaParams}",
          param.displayName()
        )
      }
  }

  protected def replaceScalaDefaultParams(
      base: String,
      defaults: Seq[String]
  ): String = {
    val matcher = "= \\.\\.\\.".r.pattern.matcher(base)
    val out = new StringBuffer()
    val it = defaults.iterator
    while (matcher.find()) {
      if (it.hasNext) {
        matcher.appendReplacement(out, s"= ${it.next()}")
      }
    }
    matcher.appendTail(out)
    out.toString
  }

}
