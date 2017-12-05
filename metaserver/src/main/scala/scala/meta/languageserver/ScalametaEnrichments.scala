package scala.meta.languageserver

import java.nio.file.Files
import java.nio.file.Paths
import java.nio.charset.StandardCharsets
import java.net.URI
import scala.{meta => m}
import langserver.types.SymbolKind
import langserver.types.TextDocumentIdentifier
import langserver.{types => l}
import scala.meta.languageserver.{index => i}
import org.langmeta.io.AbsolutePath
import org.langmeta.internal.io.FileIO

// Extension methods for convenient reuse of data conversions between
// scala.meta._ and language.types._
object ScalametaEnrichments {

  implicit class XtensionTreeLSP(val tree: m.Tree) extends AnyVal {
    import scala.meta._

    // TODO(alexey) function inside a block/if/for/etc.?
    def isFunction: Boolean = {
      val tpeOpt: Option[Type] = tree match {
        case d @ Decl.Val(_) => Some(d.decltpe)
        case d @ Decl.Var(_) => Some(d.decltpe)
        case d @ Defn.Val(_) => d.decltpe
        case d @ Defn.Var(_) => d.decltpe
        case _ => None
      }
      tpeOpt.filter(_.is[Type.Function]).nonEmpty
    }

    // NOTE: we care only about descendants of Decl, Defn and Pkg[.Object] (see documentSymbols implementation)
    def symbolKind: SymbolKind = tree match {
      case f if f.isFunction => SymbolKind.Function
      case Decl.Var(_) | Defn.Var(_) => SymbolKind.Variable
      case Decl.Val(_) | Defn.Val(_) => SymbolKind.Constant
      case Decl.Def(_) | Defn.Def(_) => SymbolKind.Method
      case Decl.Type(_) | Defn.Type(_) => SymbolKind.Field
      case Defn.Macro(_) => SymbolKind.Constructor
      case Defn.Class(_) => SymbolKind.Class
      case Defn.Trait(_) => SymbolKind.Interface
      case Defn.Object(_) => SymbolKind.Module
      case Pkg.Object(_) => SymbolKind.Namespace
      case Pkg(_) => SymbolKind.Package
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
    def contents: String = input.asInstanceOf[m.Input.VirtualFile].value
  }
  implicit class XtensionIndexPosition(val pos: i.Position) extends AnyVal {
    def pretty: String =
      s"${pos.uri.replaceFirst(".*/", "")} [${pos.range.map(_.pretty).getOrElse("")}]"

    def toLocation(implicit cwd: m.AbsolutePath): l.Location = {
      l.Location(
        pos.uri,
        pos.range.get.toRange
      )
    }
  }
  implicit class XtensionIndexRange(val range: i.Range) extends AnyVal {
    def pretty: String =
      f"${range.startLine}%2d:${range.startColumn}%2d|${range.endLine}%2d:${range.endColumn}%2d"
    def toRange: l.Range = l.Range(
      l.Position(line = range.startLine, character = range.startColumn),
      l.Position(line = range.endLine, character = range.endColumn)
    )
    def contains(line: Int, column: Int): Boolean = {
      range.startLine <= line &&
      range.startColumn <= column &&
      range.endLine >= line &&
      range.endColumn >= column
    }
  }
  implicit class XtensionAbsolutePathLSP(val path: m.AbsolutePath)
      extends AnyVal {
    def toLocation(pos: m.Position): l.Location =
      l.Location(path.toLanguageServerUri, pos.toRange)
    def toLanguageServerUri: String = "file:" + path.toString()
  }
  implicit class XtensionPositionRangeLSP(val pos: m.Position) extends AnyVal {
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
          param
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

  implicit class XtensionLocation(val loc: l.Location) extends AnyVal {

    /** A workaround for locations referring to jars */
    def toNonJar(destination: AbsolutePath): l.Location = {
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
    // TODO: Fix this with https://github.com/scalameta/language-server/issues/36
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

  implicit class XtensionSymbolData(val symbolData: i.SymbolData)
      extends AnyVal {

    /** Returns reference positions for the given symbol index data
     * @param withDefinition if set to `true` will include symbol definition location
     */
    def referencePositions(withDefinition: Boolean): Set[i.Position] = {
      val defPosition = if (withDefinition) symbolData.definition else None

      val refPositions = for {
        (uri, rangeSet) <- symbolData.references
        range <- rangeSet.ranges
      } yield i.Position(uri, Some(range))

      (defPosition.toSet ++ refPositions.toSet)
        .filterNot { _.uri.startsWith("jar:file") } // definition may refer to a jar
    }

  }
}
