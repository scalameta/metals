package langserver.types

import enumeratum.values._

sealed abstract class DiagnosticSeverity(val value: Int) extends IntEnumEntry

case object DiagnosticSeverity extends IntEnum[DiagnosticSeverity] with IntPlayJsonValueEnum[DiagnosticSeverity] {

  case object Error       extends DiagnosticSeverity(1)
  case object Warning     extends DiagnosticSeverity(2)
  case object Information extends DiagnosticSeverity(3)
  case object Hint        extends DiagnosticSeverity(4)

  val values = findValues
}

sealed abstract class CompletionItemKind(val value: Int) extends IntEnumEntry

case object CompletionItemKind extends IntEnum[CompletionItemKind] with IntPlayJsonValueEnum[CompletionItemKind] {

  case object Text        extends CompletionItemKind(1)
  case object Method      extends CompletionItemKind(2)
  case object Function    extends CompletionItemKind(3)
  case object Constructor extends CompletionItemKind(4)
  case object Field       extends CompletionItemKind(5)
  case object Variable    extends CompletionItemKind(6)
  case object Class       extends CompletionItemKind(7)
  case object Interface   extends CompletionItemKind(8)
  case object Module      extends CompletionItemKind(9)
  case object Property    extends CompletionItemKind(10)
  case object Unit        extends CompletionItemKind(11)
  case object Value       extends CompletionItemKind(12)
  case object Enum        extends CompletionItemKind(13)
  case object Keyword     extends CompletionItemKind(14)
  case object Snippet     extends CompletionItemKind(15)
  case object Color       extends CompletionItemKind(16)
  case object File        extends CompletionItemKind(17)
  case object Reference   extends CompletionItemKind(18)

  val values = findValues
}

sealed abstract class DocumentHighlightKind(val value: Int) extends IntEnumEntry

case object DocumentHighlightKind extends IntEnum[DocumentHighlightKind] with IntPlayJsonValueEnum[DocumentHighlightKind] {
  /** A textual occurrence */
  case object Text  extends DocumentHighlightKind(1)
  /** Read-access of a symbol, like reading a variable */
  case object Read  extends DocumentHighlightKind(2)
  /** Write-access of a symbol, like writing to a variable */
  case object Write extends DocumentHighlightKind(3)

  val values = findValues
}

sealed abstract class SymbolKind(val value: Int) extends IntEnumEntry

case object SymbolKind extends IntEnum[SymbolKind] with IntPlayJsonValueEnum[SymbolKind] {

  case object File        extends SymbolKind(1)
  case object Module      extends SymbolKind(2)
  case object Namespace   extends SymbolKind(3)
  case object Package     extends SymbolKind(4)
  case object Class       extends SymbolKind(5)
  case object Method      extends SymbolKind(6)
  case object Property    extends SymbolKind(7)
  case object Field       extends SymbolKind(8)
  case object Constructor extends SymbolKind(9)
  case object Enum        extends SymbolKind(10)
  case object Interface   extends SymbolKind(11)
  case object Function    extends SymbolKind(12)
  case object Variable    extends SymbolKind(13)
  case object Constant    extends SymbolKind(14)
  case object String      extends SymbolKind(15)
  case object Number      extends SymbolKind(16)
  case object Boolean     extends SymbolKind(17)
  case object Array       extends SymbolKind(18)

  val values = findValues
}

sealed abstract class MessageType(val value: Int) extends IntEnumEntry

case object MessageType extends IntEnum[MessageType] with IntPlayJsonValueEnum[MessageType] {

  /** An error message. */
  case object Error   extends MessageType(1)
  /** A warning message. */
  case object Warning extends MessageType(2)
  /** An information message. */
  case object Info    extends MessageType(3)
  /** A log message. */
  case object Log     extends MessageType(4)

  val values = findValues
}

sealed abstract class TextDocumentSyncKind(val value: Int) extends IntEnumEntry

case object TextDocumentSyncKind extends IntEnum[TextDocumentSyncKind] with IntPlayJsonValueEnum[TextDocumentSyncKind] {

  /** Documents should not be synced at all */
  case object None        extends TextDocumentSyncKind(0)
  /** Documents are synced by always sending the full content of the document */
  case object Full        extends TextDocumentSyncKind(1)
  /** Documents are synced by sending the full content on open.
    * After that only incremental updates to the document are send.
    */
  case object Incremental extends TextDocumentSyncKind(2)

  val values = findValues
}

sealed abstract class FileChangeType(val value: Int) extends IntEnumEntry

case object FileChangeType extends IntEnum[FileChangeType] with IntPlayJsonValueEnum[FileChangeType] {

  case object Created extends FileChangeType(1)
  case object Changed extends FileChangeType(2)
  case object Deleted extends FileChangeType(3)

  val values = findValues
}
