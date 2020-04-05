package scala.meta.internal.pc

import scala.meta.pc.OffsetParams
import scala.meta.pc.DefinitionResult
import scala.meta.internal.metals.CompilerOffsetParams
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import scala.collection.JavaConverters._

trait SourceModificator {
  def forDefinition(pos: OffsetParams)(
      f: OffsetParams => DefinitionResult
  ): DefinitionResult
  def forHover(pos: OffsetParams)(
      f: OffsetParams => Option[Hover]
  ): Option[Hover]
  def forCompletion(pos: OffsetParams)(
      f: OffsetParams => CompletionList
  ): CompletionList
}

object SourceModificator {

  val NoModification: SourceModificator =
    new SourceModificator {
      override def forDefinition(pos: OffsetParams)(
          f: OffsetParams => DefinitionResult
      ): DefinitionResult = f(pos)
      override def forHover(pos: OffsetParams)(
          f: OffsetParams => Option[Hover]
      ): Option[Hover] = f(pos)
      override def forCompletion(pos: OffsetParams)(
          f: OffsetParams => CompletionList
      ): CompletionList = f(pos)
    }

  def appendAutoImports(imports: Seq[String]): SourceModificator = {
    new SourceModificator {

      private val appendStr = imports.mkString("", "\n", "\n")
      private val appendLineSize = imports.size
      private val appendSize = appendStr.size

      override def forDefinition(
          pos: OffsetParams
      )(f: OffsetParams => DefinitionResult): DefinitionResult =
        f(transformPos(pos))

      override def forHover(
          pos: OffsetParams
      )(f: OffsetParams => Option[Hover]): Option[Hover] =
        f(transformPos(pos)).map(fixHover)

      override def forCompletion(
          pos: OffsetParams
      )(f: OffsetParams => CompletionList): CompletionList =
        fixCompletions(f(transformPos(pos)))

      private def transformPos(pos: OffsetParams): OffsetParams = {
        CompilerOffsetParams(
          pos.filename(),
          appendStr + pos.text(),
          appendSize + pos.offset(),
          pos.token()
        )
      }

      private def fixCompletions(
          completions: CompletionList
      ): CompletionList = {
        val items = completions
          .getItems()
          .asScala
          .map(item => {
            item.setTextEdit(fixTextEdit(item.getTextEdit))
            item
          })
          .asJava
        new CompletionList(items)
      }

      private def fixHover(hover: Hover): Hover = {
        hover.setRange(fixRange(hover.getRange))
        hover
      }

      private def fixTextEdit(te: TextEdit): TextEdit = {
        new TextEdit(fixRange(te.getRange()), te.getNewText())
      }

      private def fixRange(range: Range): Range = {
        def changeLine(pos: Position): Position =
          new Position(pos.getLine - appendLineSize, pos.getCharacter)

        val start = range.getStart
        val end = range.getEnd
        new Range(changeLine(start), changeLine(end))
      }
    }
  }
}
