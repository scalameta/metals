package langserver.types

import play.api.libs.json._
import play.api.libs.json.OFormat

/**
 * Position in a text document expressed as zero-based line and character offset.
 */
case class Position(line: Int, character: Int)
object Position { implicit val format: OFormat[Position] = Json.format[Position] }

/**
 * A range in a text document.
 */
case class Range(start: Position, end: Position)
object Range { implicit val format: OFormat[Range] = Json.format[Range] }

/**
 * Represents a location inside a resource, such as a line
 * inside a text file.
 */
case class Location(uri: String, range: Range)
object Location { implicit val format: OFormat[Location] = Json.format[Location] }

/**
  * Represents a diagnostic, such as a compiler error or warning. Diagnostic objects are only valid
  * in the scope of a resource.
  *
  * @param range the range at which this diagnostic applies
  * @param severity severity of this diagnostics (see above)
  * @param code a code for this diagnostic
  * @param source the source of this diagnostic (like 'typescript' or 'scala')
  * @param message the diagnostic message
  */
case class Diagnostic(
  range: Range,
  severity: Option[DiagnosticSeverity],
  code: Option[String],
  source: Option[String],
  message: String
)
object Diagnostic {
  implicit val format: OFormat[Diagnostic] = Json.format[Diagnostic]
}

/**
 * A reference to a command.
 *
 * @param title The title of the command, like 'Save'
 * @param command The identifier of the actual command handler
 * @param arguments The arugments this command may be invoked with
 */
case class Command(title: String, command: String, arguments: Seq[JsValue])
object Command {
  implicit val format: OFormat[Command] = Json.format[Command]
}

case class TextEdit(range: Range, newText: String)

object TextEdit {
  implicit val formatter: OFormat[TextEdit] = Json.format[TextEdit]
}

/**
 * A workspace edit represents changes to many resources managed
 * in the workspace.
 */
case class WorkspaceEdit(
  changes: Map[String, Seq[TextEdit]] // uri -> changes
)
object WorkspaceEdit {
  implicit val format: OFormat[WorkspaceEdit] = Json.format[WorkspaceEdit]
}

case class TextDocumentIdentifier(uri: String)
object TextDocumentIdentifier { implicit val format: OFormat[TextDocumentIdentifier] = Json.format[TextDocumentIdentifier] }

case class VersionedTextDocumentIdentifier(uri: String, version: Long)
object VersionedTextDocumentIdentifier { implicit val format: OFormat[VersionedTextDocumentIdentifier] = Json.format[VersionedTextDocumentIdentifier] }

/**
 * An item to transfer a text document from the client to the
 * server.
 */
case class TextDocumentItem(
  uri: String,
  languageId: String,
  /**
   * The version number of this document (it will strictly increase after each
   * change, including undo/redo).
   */
  version: Long,
  text: String)

object TextDocumentItem {
  implicit val format: OFormat[TextDocumentItem] = Json.format[TextDocumentItem]
}

case class CompletionItem(
  label: String,
  kind: Option[CompletionItemKind] = None,
  detail: Option[String] = None,
  documentation: Option[String] = None,
  sortText: Option[String] = None,
  filterText: Option[String] = None,
  insertText: Option[String] = None,
  textEdit: Option[String] = None,
  data: Option[String] = None) // An data entry field that is preserved on a completion item between
// a [CompletionRequest](#CompletionRequest) and a [CompletionResolveRequest]
//   (#CompletionResolveRequest)

object CompletionItem {
  implicit def format: OFormat[CompletionItem] = Json.format[CompletionItem]
}

trait MarkedString

case class RawMarkedString(language: String, value: String) extends MarkedString {
  def this(value: String) {
    this("text", value)
  }
}

case class MarkdownString(contents: String) extends MarkedString

object MarkedString {
  implicit val reads: Reads[MarkedString] =
    Json.reads[RawMarkedString].map(x => x: MarkedString).orElse(Json.reads[MarkdownString].map(x => x: MarkedString))

  implicit val writes: Writes[MarkedString] = Writes[MarkedString] {
    case raw: RawMarkedString => Json.writes[RawMarkedString].writes(raw)
    case md: MarkdownString => Json.writes[MarkdownString].writes(md)
  }
}


case class ParameterInformation(label: String, documentation: Option[String])
object ParameterInformation {
  implicit val format: OFormat[ParameterInformation] = Json.format[ParameterInformation]
}

case class SignatureInformation(label: String, documentation: Option[String], parameters: Seq[ParameterInformation])
object SignatureInformation {
  implicit val format: OFormat[SignatureInformation] = Json.format[SignatureInformation]
}


/**
 * Value-object that contains additional information when
 * requesting references.
 */
case class ReferenceContext(
  /** Include the declaration of the current symbol. */
  includeDeclaration: Boolean
)
object ReferenceContext {
  implicit var format: OFormat[ReferenceContext] = Json.format[ReferenceContext]
}

/**
 * A document highlight is a range inside a text document which deserves
 * special attention. Usually a document highlight is visualized by changing
 * the background color of its range.
 */
case class DocumentHighlight(
  /** The range this highlight applies to. */
  range: Range,

  /** The highlight kind, default is [text](#DocumentHighlightKind.Text). */
  kind: DocumentHighlightKind = DocumentHighlightKind.Text)

case class SymbolInformation(
  name: String,
  kind: SymbolKind,
  location: Location,
  containerName: Option[String])
object SymbolInformation {
  implicit val format: OFormat[SymbolInformation] = Json.format[SymbolInformation]
}

/**
 * The parameters of a [WorkspaceSymbolRequest](#WorkspaceSymbolRequest).
 */
case class WorkspaceSymbolParams(query: String)
object WorkspaceSymbolParams {
  implicit val format: OFormat[WorkspaceSymbolParams] = Json.format[WorkspaceSymbolParams]
}

case class CodeActionContext(diagnostics: Seq[Diagnostic])
object CodeActionContext {
  implicit val format: OFormat[CodeActionContext] = Json.format[CodeActionContext]
}

/**
 * A code lens represents a [command](#Command) that should be shown along with
 * source text, like the number of references, a way to run tests, etc.
 *
 * A code lens is _unresolved_ when no command is associated to it. For performance
 * reasons the creation of a code lens and resolving should be done to two stages.
 */
case class CodeLens(
  /**
   * The range in which this code lens is valid. Should only span a single line.
   */
  range: Range,

  /**
   * The command this code lens represents.
   */
  command: Option[Command],

  /**
   * An data entry field that is preserved on a code lens item between
   * a [CodeLensRequest](#CodeLensRequest) and a [CodeLensResolveRequest]
   * (#CodeLensResolveRequest)
   */
  data: Option[Any])

/**
 * Value-object describing what options formatting should use.
 */
case class FormattingOptions(
  /**
   * Size of a tab in spaces.
   */
  tabSize: Int,

  /**
   * Prefer spaces over tabs.
   */
  insertSpaces: Boolean,

  /**
   * Signature for further properties.
   */
  // params: Map[String, Any] // [key: string]: boolean | number | string;
)

object FormattingOptions {
  implicit val formatter: OFormat[FormattingOptions] = Json.format[FormattingOptions]
}

trait TextDocument {
  /**
   * The associated URI for this document. Most documents have the __file__-scheme, indicating that they
   * represent files on disk. However, some documents may have other schemes indicating that they are not
   * available on disk.
   */
  val uri: String

  /** The identifier of the language associated with this document. */
  val languageId: String

  /**
   * The version number of this document (it will strictly increase after each
   * change, including undo/redo).
   */
  def version: Long

  /**
   * Get the text of this document.
   *
   * @return The text of this document.
   */
  def getText(): String

  /**
   * Converts a zero-based offset to a position.
   *
   * @param offset A zero-based offset.
   * @return A valid [position](#Position).
   */
  def positionAt(offset: Int): Position

  /**
   * Converts the position to a zero-based offset.
   *
   * The position will be [adjusted](#TextDocument.validatePosition).
   *
   * @param position A position.
   * @return A valid zero-based offset.
   */
  def offsetAt(position: Position): Int;

  /** The number of lines in this document. */
  def lineCount: Int;
}

case class TextDocumentChangeEvent(document: TextDocument)

/**
 * An event describing a change to a text document. If range and rangeLength are omitted
 * the new text is considered to be the full content of the document.
 */
case class TextDocumentContentChangeEvent(
  /**
   * The range of the document that changed.
   */
  range: Option[Range],

  /**
   * The length of the range that got replaced.
   */
  rangeLength: Option[Int],

  /**
   * The new text of the document.
   */
  text: String
)

object TextDocumentContentChangeEvent {
  implicit val format: OFormat[TextDocumentContentChangeEvent] = Json.format[TextDocumentContentChangeEvent]
}

case class DocumentFormattingParams(
  /**
   * The document to format.
   */
  textDocument: TextDocumentIdentifier,

  /**
   * The format options.
   */
  options: FormattingOptions
)

object DocumentFormattingParams {
  implicit val format: OFormat[DocumentFormattingParams] = Json.format[DocumentFormattingParams]
}

case class WorkspaceExecuteCommandParams(command: String, arguments: Option[Seq[JsValue]])
object WorkspaceExecuteCommandParams {
  implicit val format: OFormat[WorkspaceExecuteCommandParams] = Json.format[WorkspaceExecuteCommandParams]
}


/**
  * An event describing a file change.
  *
  * @param uri The file's URI
  * @param `type` The change type
  */
case class FileEvent(
  uri: String,
  `type`: FileChangeType
)
object FileEvent {
  implicit val format: OFormat[FileEvent] = Json.format[FileEvent]
}
