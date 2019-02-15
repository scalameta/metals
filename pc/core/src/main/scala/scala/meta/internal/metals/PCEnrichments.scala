package scala.meta.internal.metals

import com.google.gson.Gson
import com.google.gson.JsonElement
import java.util.logging.Level
import java.util.logging.Logger
import org.eclipse.lsp4j.CompletionItem
import scala.meta.internal.{semanticdb => s}
import org.eclipse.{lsp4j => l}
import scala.meta.internal.pc.CompletionItemData
import scala.meta.internal.semanticdb.SymbolInformation.{Kind => k}
import scala.util.control.NonFatal

/**
 * Extension methods used for interfacing with the presentation compiler.
 */
object PCEnrichments extends PCEnrichments
trait PCEnrichments {

  private def logger: Logger = Logger.getLogger(classOf[PCEnrichments].getName)

  protected def decodeJson[T](
      obj: AnyRef,
      cls: java.lang.Class[T]
  ): Option[T] =
    for {
      data <- Option(obj)
      value <- try {
        Some(
          new Gson().fromJson[T](
            data.asInstanceOf[JsonElement],
            cls
          )
        )
      } catch {
        case NonFatal(e) =>
          logger.log(Level.SEVERE, s"decode error: $cls", e)
          None
      }
    } yield value

  implicit class XtensionCompletionItemData(item: CompletionItem) {
    def data: Option[CompletionItemData] =
      item.getData match {
        case d: CompletionItemData =>
          Some(d)
        case data =>
          decodeJson(data, classOf[CompletionItemData])
      }
  }
  implicit class XtensionRangeBuildProtocol(range: s.Range) {
    def toLocation(uri: String): l.Location = {
      new l.Location(uri, range.toLSP)
    }
    def toLSP: l.Range = {
      val start = new l.Position(range.startLine, range.startCharacter)
      val end = new l.Position(range.endLine, range.endCharacter)
      new l.Range(start, end)
    }
    def encloses(other: l.Position): Boolean = {
      range.startLine <= other.getLine &&
      range.endLine >= other.getLine &&
      range.startCharacter <= other.getCharacter &&
      range.endCharacter > other.getCharacter
    }
    def encloses(other: l.Range): Boolean = {
      encloses(other.getStart) &&
      encloses(other.getEnd)
    }
  }

  implicit class XtensionSymbolInformation(kind: s.SymbolInformation.Kind) {
    def toLSP: l.SymbolKind = kind match {
      case k.LOCAL => l.SymbolKind.Variable
      case k.FIELD => l.SymbolKind.Field
      case k.METHOD => l.SymbolKind.Method
      case k.CONSTRUCTOR => l.SymbolKind.Constructor
      case k.MACRO => l.SymbolKind.Method
      case k.TYPE => l.SymbolKind.Class
      case k.PARAMETER => l.SymbolKind.Variable
      case k.SELF_PARAMETER => l.SymbolKind.Variable
      case k.TYPE_PARAMETER => l.SymbolKind.TypeParameter
      case k.OBJECT => l.SymbolKind.Object
      case k.PACKAGE => l.SymbolKind.Module
      case k.PACKAGE_OBJECT => l.SymbolKind.Module
      case k.CLASS => l.SymbolKind.Class
      case k.TRAIT => l.SymbolKind.Interface
      case k.INTERFACE => l.SymbolKind.Interface
      case _ => l.SymbolKind.Class
    }
  }
}
