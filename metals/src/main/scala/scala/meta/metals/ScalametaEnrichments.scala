package scala.meta.metals

import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import org.langmeta.lsp.Diagnostic
import org.langmeta.lsp.Location
import org.langmeta.lsp.Position
import scala.meta.metals.{index => i}
import scala.meta.internal.semanticdb3
import scala.{meta => m}
import org.langmeta.lsp.SymbolKind
import org.langmeta.{lsp => l}
import org.langmeta.internal.io.FileIO
import org.langmeta.io.AbsolutePath
import org.langmeta.internal.semanticdb._
import scala.meta.internal.semanticdb3.SymbolInformation.Kind
import scala.meta.internal.semanticdb3.SymbolInformation.Property
import scala.meta.metals.search.SymbolInformationsBySymbol

// Extension methods for convenient reuse of data conversions between
// scala.meta._ and language.types._
object ScalametaEnrichments {

  implicit class XtensionMessageLSP(val msg: m.Message) extends AnyVal {
    def toLSP(source: String): Diagnostic =
      l.Diagnostic(
        range = msg.position.toRange,
        severity = Some(msg.severity.toLSP),
        code = None,
        source = Some(source),
        message = msg.text
      )
  }

  implicit class XtensionSeverityLSP(val severity: m.Severity) extends AnyVal {
    def toLSP: l.DiagnosticSeverity = severity match {
      case m.Severity.Info => l.DiagnosticSeverity.Information
      case m.Severity.Warning => l.DiagnosticSeverity.Warning
      case m.Severity.Error => l.DiagnosticSeverity.Error
      case m.Severity.Hint => l.DiagnosticSeverity.Hint
    }
  }

  implicit class XtensionTreeLSP(val tree: m.Tree) extends AnyVal {
    import scala.meta._

    // TODO(alexey) function inside a block/if/for/etc.?
    def isFunction: Boolean = {
      val tpeOpt: Option[Type] = tree match {
        case d: Decl.Val => Some(d.decltpe)
        case d: Decl.Var => Some(d.decltpe)
        case d: Defn.Val => d.decltpe
        case d: Defn.Var => d.decltpe
        case _ => None
      }
      tpeOpt.exists(_.is[Type.Function])
    }

    // NOTE: we care only about descendants of Decl, Defn and Pkg[.Object] (see documentSymbols implementation)
    def symbolKind: SymbolKind = tree match {
      case f if f.isFunction => SymbolKind.Function
      case _: Decl.Var | _: Defn.Var => SymbolKind.Variable
      case _: Decl.Val | _: Defn.Val => SymbolKind.Constant
      case _: Decl.Def | _: Defn.Def => SymbolKind.Method
      case _: Decl.Type | _: Defn.Type => SymbolKind.Field
      case _: Defn.Macro => SymbolKind.Constructor
      case _: Defn.Class => SymbolKind.Class
      case _: Defn.Trait => SymbolKind.Interface
      case _: Defn.Object => SymbolKind.Module
      case _: Pkg.Object => SymbolKind.Namespace
      case _: Pkg => SymbolKind.Package
      case _: Type.Param => SymbolKind.TypeParameter
      case _: Lit.Null => SymbolKind.Null
      // TODO(alexey) are these kinds useful?
      // case ??? => SymbolKind.Enum
      // case ??? => SymbolKind.String
      // case ??? => SymbolKind.Number
      // case ??? => SymbolKind.Boolean
      // case ??? => SymbolKind.Array
      case _ => SymbolKind.Field
    }

    /** Fully qualified name for packages, normal name for everything else */
    def qualifiedName: Option[String] = tree match {
      case Term.Name(name) => Some(name)
      case Term.Select(qual, name) =>
        qual.qualifiedName.map { prefix =>
          s"${prefix}.${name}"
        }
      case Pkg(sel: Term.Select, _) => sel.qualifiedName
      case m: Member => Some(m.name.value)
      case _ => None
    }

    /** All names within the node.
     * - if it's a package, it will have its qualified name: `package foo.bar.buh`
     * - if it's a val/var, it may contain several names in the pattern: `val (x, y, z) = ...`
     * - for everything else it's just its normal name (if it has one)
     */
    private def patternNames(pats: List[Pat]): Seq[String] =
      pats.flatMap { _.collect { case Pat.Var(name) => name.value } }
    def names: Seq[String] = tree match {
      case t: Pkg => t.qualifiedName.toSeq
      case t: Defn.Val => patternNames(t.pats)
      case t: Decl.Val => patternNames(t.pats)
      case t: Defn.Var => patternNames(t.pats)
      case t: Decl.Var => patternNames(t.pats)
      case t: Member => Seq(t.name.value)
      case _ => Seq()
    }
  }
  implicit class XtensionInputLSP(val input: m.Input) extends AnyVal {
    def contents: String = input match {
      case m.Input.VirtualFile(_, value) => value
      case _ => new String(input.chars)
    }
  }
  implicit class XtensionIndexPosition(val pos: l.Location) extends AnyVal {
    def pretty: String =
      s"${pos.uri.replaceFirst(".*/", "")} [${pos.range.pretty}]"

    def toLocation: Location = {
      l.Location(
        pos.uri,
        pos.range.toRange
      )
    }
  }
  implicit class XtensionIndexRange(val range: l.Range) extends AnyVal {
    def pretty: String =
      f"${range.startLine}%3d:${range.startColumn}%3d|${range.endLine}%3d:${range.endColumn}%3d"
    def toRange: l.Range = l.Range(
      Position(line = range.startLine, character = range.startColumn),
      l.Position(line = range.endLine, character = range.endColumn)
    )
    def contains(pos: m.Position): Boolean = {
      range.startLine <= pos.startLine &&
      range.startColumn <= pos.startColumn &&
      range.endLine >= pos.endLine &&
      range.endColumn >= pos.endColumn
    }
    def contains(line: Int, column: Int): Boolean = {
      range.startLine <= line &&
      range.startColumn <= column &&
      range.endLine >= line &&
      range.endColumn >= column
    }
  }
  implicit class XtensionAbsolutePathLSP(val path: m.AbsolutePath)
      extends AnyVal {
    def toLocation(pos: m.Position): Location =
      l.Location(path.toLanguageServerUri, pos.toRange)
    def toLanguageServerUri: String = "file:" + path.toString()
  }
  implicit class XtensionPositionRangeLSP(val pos: m.Position) extends AnyVal {
    def toSchemaRange: semanticdb3.Range = {
      semanticdb3.Range(
        startLine = pos.startLine,
        startCharacter = pos.startColumn,
        endLine = pos.endLine,
        endCharacter = pos.endColumn
      )
    }
    def toIndexRange: l.Range = l.Range(
      startLine = pos.startLine,
      startColumn = pos.startColumn,
      endLine = pos.endLine,
      endColumn = pos.endColumn
    )
    def contains(offset: Int): Boolean =
      if (pos.start == pos.end) pos.end == offset
      else {
        pos.start <= offset &&
        pos.end > offset
      }
    def location: String =
      s"${pos.input.syntax}:${pos.startLine}:${pos.startColumn}"
    def toRange: l.Range = l.Range(
      l.Position(line = pos.startLine, character = pos.startColumn),
      l.Position(line = pos.endLine, character = pos.endColumn)
    )
  }
  implicit class XtensionSymbolGlobalTerm(val sym: m.Symbol.Global)
      extends AnyVal {
    def toType: m.Symbol.Global = sym match {
      case m.Symbol.Global(owner, m.Signature.Term(name)) =>
        m.Symbol.Global(owner, m.Signature.Type(name))
      case _ => sym
    }
    def toTerm: m.Symbol.Global = sym match {
      case m.Symbol.Global(owner, m.Signature.Type(name)) =>
        m.Symbol.Global(owner, m.Signature.Term(name))
      case _ => sym
    }
  }
  implicit class XtensionSymbol(val sym: m.Symbol) extends AnyVal {
    import scala.meta._

    /** Returns a list of fallback symbols that can act instead of given symbol. */
    // TODO(alexey) review/refine this list
    def referenceAlternatives: List[Symbol] = {
      List(
        caseClassCompanionToType,
        caseClassApplyOrCopyParams
      ).flatten
    }

    /** Returns a list of fallback symbols that can act instead of given symbol. */
    // TODO(alexey) review/refine this list
    def definitionAlternative: List[Symbol] = {
      List(
        caseClassCompanionToType,
        caseClassApplyOrCopy,
        caseClassApplyOrCopyParams,
        methodToVal
      ).flatten
    }

    /** If `case class A(a: Int)` and there is no companion object, resolve
     * `A` in `A(1)` to the class definition.
     */
    def caseClassCompanionToType: Option[Symbol] = Option(sym).collect {
      case Symbol.Global(owner, Signature.Term(name)) =>
        Symbol.Global(owner, Signature.Type(name))
    }

    /** If `case class Foo(a: Int)`, then resolve
     * `a` in `Foo.apply(a = 1)`, and
     * `a` in `Foo(1).copy(a = 2)`
     * to the `Foo.a` primary constructor definition.
     */
    def caseClassApplyOrCopyParams: Option[Symbol] = Option(sym).collect {
      case Symbol.Global(
          Symbol.Global(
            Symbol.Global(owner, signature),
            Signature.Method("copy" | "apply", _)
          ),
          param: Signature.TermParameter
          ) =>
        Symbol.Global(
          Symbol.Global(owner, Signature.Type(signature.name)),
          Signature.Method(param.name, "()")
        )
    }

    /** If `case class Foo(a: Int)`, then resolve
     * `apply` in `Foo.apply(1)`, and
     * `copy` in `Foo(1).copy(a = 2)`
     * to the `Foo` class definition.
     */
    def caseClassApplyOrCopy: Option[Symbol] = Option(sym).collect {
      case Symbol.Global(
          Symbol.Global(owner, signature),
          Signature.Method("apply" | "copy", _)
          ) =>
        Symbol.Global(owner, Signature.Type(signature.name))
    }

    /** Fallback to the val term for a def with multiple params */
    def methodToVal: Option[Symbol] = Option(sym).collect {
      case Symbol.Global(owner, Signature.Method(name, _)) =>
        Symbol.Global(owner, Signature.Term(name))
    }
  }

  implicit class XtensionLocation(val loc: Location) extends AnyVal {

    /** A workaround for locations referring to jars */
    def toNonJar(destination: AbsolutePath): Location = {
      if (loc.uri.startsWith("jar:file")) {
        val newURI =
          createFileInWorkspaceTarget(URI.create(loc.uri), destination)
        loc.copy(uri = newURI.toString)
      } else loc
    }

    // Writes the contents from in-memory source file to a file in the target/source/*
    // directory of the workspace. vscode has support for TextDocumentContentProvider
    // which can provide hooks to open readonly views for custom uri schemes:
    // https://code.visualstudio.com/docs/extensionAPI/vscode-api#TextDocumentContentProvider
    // However, that is a vscode only solution and we'd like this work for all
    // text editors. Therefore, we write instead the file contents to disk in order to
    // return a file: uri.
    // TODO: Fix this with https://github.com/scalameta/metals/issues/36
    private def createFileInWorkspaceTarget(
        uri: URI,
        destination: AbsolutePath
    ): URI = {
      // logger.info(s"Jumping into uri $uri, writing contents to file in target file")
      val contents =
        new String(FileIO.readAllBytes(uri), StandardCharsets.UTF_8)
      // HACK(olafur) URIs are not typesafe, jar:file://blah.scala will return
      // null for `.getPath`. We should come up with nicer APIs to deal with this
      // kinda stuff.
      val path: String =
        if (uri.getPath == null)
          uri.getSchemeSpecificPart
        else uri.getPath
      val filename = Paths.get(path).getFileName

      Files.createDirectories(destination.toNIO)
      val out = destination.toNIO.resolve(filename)
      Files.write(out, contents.getBytes(StandardCharsets.UTF_8))
      out.toUri
    }
  }

  implicit class XtensionSymbolData(val data: i.SymbolData) extends AnyVal {

    /** Returns reference positions for the given symbol index data
     * @param withDefinition if set to `true` will include symbol definition location
     */
    def referencePositions(withDefinition: Boolean): Set[l.Location] = {
      val defPosition = if (withDefinition) data.definition else None

      val refPositions = for {
        (uri, ranges) <- data.references
        range <- ranges
      } yield l.Location(uri.value, range)

      (defPosition.toSet ++ refPositions.toSet)
        .filterNot { _.uri.startsWith("jar:file") } // definition may refer to a jar
    }

  }
  implicit class XtensionSchemaRange(val r: semanticdb3.Range) {
    def toLSP: l.Range = l.Range(
      startLine = r.startLine,
      startColumn = r.startCharacter,
      endLine = r.endLine,
      endColumn = r.endCharacter
    )
    def toLocation(uri: String): l.Location = {
      l.Location(uri, toLSP)
    }
  }
  implicit class XtensionSchemaDocument(val document: semanticdb3.TextDocument)
      extends AnyVal {

    def computeSymbolDataForLocalSymbol(
        symbol: String
    ): Option[i.SymbolData] = {
      info(symbol).map { info =>
        val uri = Uri(document.uri)
        var definition: Option[Location] = None
        val references = List.newBuilder[l.Range]
        document.occurrences.foreach { o =>
          if (o.symbol == symbol && o.range.isDefined) {
            if (o.role.isDefinition) {
              definition = Some(o.range.get.toLocation(document.uri))
            } else if (o.role.isReference) {
              references += o.range.get.toLSP
            }
          }
        }
        i.SymbolData(
          symbol = symbol,
          definition = definition,
          references = Map(uri -> references.result()),
          info = Some(info)
        )
      }
    }

    def info(symbol: String): Option[semanticdb3.SymbolInformation] = {
      document.symbols match {
        case s: SymbolInformationsBySymbol =>
          s.lookupSymbol(symbol)
        case _ =>
          document.symbols.find(_.symbol == symbol)
      }
    }

    /** Returns scala.meta.Document from protobuf schema.Document */
    def toMetaDocument: m.Document =
      semanticdb3.TextDocuments(document :: Nil).toDb(None).documents.head
  }

  implicit class XtensionSymbolInformation(
      val info: semanticdb3.SymbolInformation
  ) extends AnyVal {
    import Property._

    def isLocal: Boolean = {
      info.kind.isLocal ||
      // Workaround for https://github.com/scalameta/scalameta/issues/1486
      info.symbol.startsWith("local")
    }

    def isOneOf(kind: Kind*): Boolean = {
      kind.contains(info.kind)
    }
    def has(prop1: Property, prop2: Property, props: Property*): Boolean =
      has(prop1) || has(prop2) || props.exists(has)
    def has(prop: Property): Boolean =
      info.properties.hasOneOfFlags(prop.value)
    def toSymbolKind: SymbolKind = info.kind match {
      case Kind.OBJECT | Kind.PACKAGE_OBJECT =>
        SymbolKind.Module
      case Kind.CLASS =>
        if (has(ENUM)) SymbolKind.Enum
        else SymbolKind.Class
      case Kind.PACKAGE =>
        SymbolKind.Package
      case Kind.TRAIT | Kind.INTERFACE =>
        SymbolKind.Interface
      case Kind.METHOD | Kind.LOCAL =>
        if (has(VAL)) SymbolKind.Constant
        else if (has(VAR)) SymbolKind.Variable
        else SymbolKind.Method
      case Kind.MACRO =>
        SymbolKind.Method
      case Kind.CONSTRUCTOR =>
        SymbolKind.Constructor
      case Kind.FIELD =>
        SymbolKind.Field
      case Kind.TYPE =>
        SymbolKind.Class // ???
      case Kind.PARAMETER | Kind.SELF_PARAMETER | Kind.TYPE_PARAMETER =>
        SymbolKind.Variable // ???
      case unknown @ (Kind.UNKNOWN_KIND | Kind.Unrecognized(_)) =>
        throw new IllegalArgumentException(
          s"Unsupported kind $unknown in SymbolInformation: ${info.toProtoString}"
        )
    }
  }

  implicit class XtensionIntAsSymbolKind(val flags: Int) extends AnyVal {
    def hasOneOfFlags(flags: Long): Boolean =
      (this.flags & flags) != 0L
    def toSymbolKind: SymbolKind =
      if (hasOneOfFlags(Kind.CLASS.value))
        SymbolKind.Class
      else if (hasOneOfFlags(Kind.TRAIT.value | Kind.INTERFACE.value))
        SymbolKind.Interface
      else
        SymbolKind.Module
  }

}
