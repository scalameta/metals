package scala.meta.internal.parsing

import java.util

import scala.collection.mutable

import scala.meta._
import scala.meta.inputs.Position
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.PositionSyntax.XtensionPositionsScalafix
import scala.meta.internal.mtags.Mtags
import scala.meta.internal.mtags.Symbol
import scala.meta.io.AbsolutePath
import scala.meta.transversers.SimpleTraverser
import scala.meta.trees.Origin

import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.{lsp4j => l}

/**
 *  Retrieves all the symbols defined in a document
 *
 *  If the document doesn't parse, we fall back to the latest
 *  known snapshot of the document, if present.
 */
final class DocumentSymbolProvider(
    trees: Trees,
    mtags: () => Mtags,
    buffers: Buffers,
    supportsHierarchicalDocumentSymbols: Boolean,
) {

  def documentSymbols(
      path: AbsolutePath
  ): Either[util.List[DocumentSymbol], util.List[l.SymbolInformation]] = {
    val symbols: Seq[DocumentSymbol] =
      if (path.isScalaFilename) scalaDocumentSymbols(path)
      else if (path.isJavaFilename) javaDocumentSymbols(path)
      else {
        scribe.warn(s"documentsymbol: unsupported file type $path")
        Nil
      }
    if (supportsHierarchicalDocumentSymbols) {
      Left(symbols.asJava)
    } else {
      val infos = symbols.toSymbolInformation(path.toURI.toString())
      Right(infos.asJava)
    }
  }

  private def javaDocumentSymbols(path: AbsolutePath): Seq[DocumentSymbol] = {
    val input = path.toInputFromBuffers(buffers)
    val doc = mtags().index(input, dialects.Scala213)
    val occurrences =
      doc.occurrences.iterator.map(occ => occ.symbol -> occ).toMap
    val symbols = (for {
      info <- doc.symbols.iterator
      occ <- occurrences.get(info.symbol).iterator
      range <- occ.range.iterator
      pos <- range.toMeta(input).orElse {
        scribe.warn(
          s"document-symbol: range is undefined for symbol ${info.symbol}. info=${info} occ=${occ}"
        )
        None
      }
    } yield {
      val detail = pos.lineContent
      val name =
        if (info.kind.isConstructor) Symbol(info.symbol).owner.displayName
        else info.displayName
      info.symbol -> new DocumentSymbol(
        name,
        info.kind.toLsp,
        range.toLsp,
        // TODO: handle selection range. This is missing in the upstream
        // SemanticDB proto definitions.  We should fix this by adding it to
        // semanticdb.proto in this repo, and move Mtags to using the
        // protobuf-java bindings instead of the upstream
        // org.scalameta:semanticdb_2.13 bindings.
        range.toLsp,
        detail,
        new util.ArrayList[DocumentSymbol](),
      )
    }).toMap
    val isChild = mutable.Set.empty[String]
    for {
      (sym, child) <- symbols.iterator
      owner <- symbols.get(Symbol(sym).owner.value).iterator
    } {
      isChild.add(sym)
      owner.getChildren().add(child)
    }

    (for {
      (sym, root) <- symbols.iterator
      if !isChild.contains(sym)
    } yield compressPackages(root)).toSeq
  }

  // By default, we generate a symbol hierarchy where each part of the package
  // name (`com.example.foo`) would be a separate DocumentSymbol, which is
  // tedious to manually expand. This function compresses those symbols into one
  // node in the hierarchy, which is what most users expect. However, we still
  // handle the trickier case where Scala allows defining multiple packages in
  // the same source file.
  private def compressPackages(sym: DocumentSymbol): DocumentSymbol = {
    if (
      sym.getKind() == SymbolKind.Module &&
      sym.getChildren().size() == 1
    ) {
      val child = sym.getChildren().get(0)
      if (child.getKind() == SymbolKind.Module) {
        val newName = s"${sym.getName()}.${child.getName()}"
        child.setName(newName)
        return compressPackages(child)
      }
    }

    sym
  }

  private def scalaDocumentSymbols(path: AbsolutePath): Seq[DocumentSymbol] = {
    val result = for {
      tree <- trees.get(path)
    } yield {
      implicit val dialect = tree.origin match {
        case parsed: Origin.Parsed => parsed.dialect
        case Origin.None => dialects.Scala213
        case _ => dialects.Scala213
      }
      new SymbolTraverser().symbols(tree).asScala
    }
    result.getOrElse(Nil).toSeq
  }

  private class SymbolTraverser(implicit dialect: Dialect)
      extends SimpleTraverser {
    var owner: DocumentSymbol = new DocumentSymbol(
      "root",
      SymbolKind.Namespace,
      new l.Range(new l.Position(0, 0), new l.Position(0, 0)),
      new l.Range(new l.Position(0, 0), new l.Position(0, 0)),
      "",
      new util.ArrayList[DocumentSymbol](),
    )
    def symbols(tree: Tree): util.List[DocumentSymbol] = {
      apply(tree)
      owner.getChildren
    }

    def addChild(
        name: String,
        kind: SymbolKind,
        range: Position,
        selection: Position,
        detail: String,
    ): Unit = {
      owner.getChildren.add(
        new DocumentSymbol(
          if (name.isEmpty) " " else name,
          kind,
          range.toLsp,
          selection.toLsp,
          detail,
          new util.ArrayList[DocumentSymbol](),
        )
      )
    }
    def addPats(
        pats: List[Pat],
        kind: SymbolKind,
        range: Position,
        detail: String,
    ): Unit = {
      pats.foreach { pat =>
        pat.collect { case Pat.Var(name) =>
          addChild(
            name.value,
            kind,
            range,
            name.pos,
            detail,
          )
        }
      }
    }

    override def apply(tree: Tree): Unit = {
      def continue(withNewOwner: Boolean = false): Unit = {
        val oldRoot = owner
        val children = owner.getChildren.asScala
        val hasChildren = children.nonEmpty
        if (withNewOwner && hasChildren) owner = children.last
        super.apply(tree)
        owner = oldRoot
      }
      def newOwner(): Unit = {
        continue(withNewOwner = true)
      }
      tree match {
        case t: Pkg =>
          addChild(t.ref.syntax, SymbolKind.Package, t.pos, t.ref.pos, "")
          newOwner()
        case t: Term.NewAnonymous =>
          val (name, selection) = t.templ.inits match {
            case Nil => ("(anonymous)", t.pos)
            case inits =>
              (inits.map(_.tpe.syntax).mkString(" with "), inits.head.pos)
          }
          if (t.templ.stats.nonEmpty) {
            addChild(s"new $name", SymbolKind.Interface, t.pos, selection, "")
            newOwner()
          } else continue()
        case _: Source | _: Template | _: MultiSource | _: Pkg.Body |
            _: Template.Body | _: Tree.CasesBlock =>
          continue()
        case block: Term.Block =>
          if (owner.getName() == "try") {
            if (owner.getChildren().isEmpty()) {
              continue()
            } else {
              addChild("finally", SymbolKind.Struct, block.pos, block.pos, "")
              newOwner()
            }
          } else {
            continue()
          }
        case t: Case =>
          if (
            owner.getName() == "try" && owner
              .getChildren()
              .asScala
              .forall(_.getName() != "catch")
          )
            addChild("catch", SymbolKind.Struct, t.pos, t.pos, "")
        case t: Term
            if t.isInstanceOf[Term.Try] || t
              .isInstanceOf[Term.TryWithHandler] =>
          if (t.children.nonEmpty) {
            addChild("try", SymbolKind.Struct, t.pos, t.pos, "")
            newOwner()
          } else {
            continue()
          }
        case t: Defn.Class =>
          addChild(
            t.name.value,
            SymbolKind.Class,
            t.pos,
            t.name.pos,
            "",
          )
          newOwner()
        case t: Defn.Trait =>
          addChild(
            t.name.value,
            SymbolKind.Interface,
            t.pos,
            t.name.pos,
            "",
          )
          newOwner()
        case t: Defn.Object =>
          addChild(
            t.name.value,
            SymbolKind.Module,
            t.pos,
            t.name.pos,
            "",
          )
          newOwner()
        case t: Pkg.Object =>
          addChild(
            t.name.value,
            SymbolKind.Module,
            t.pos,
            t.name.pos,
            "",
          )
          newOwner()
        case t: Defn.Def =>
          addChild(
            t.name.value,
            SymbolKind.Method,
            t.pos,
            t.name.pos,
            t.decltpe.fold("")(_.syntax),
          )
          newOwner()
        case t: Decl.Def =>
          addChild(
            t.name.value,
            SymbolKind.Method,
            t.pos,
            t.name.pos,
            t.decltpe.syntax,
          )
          newOwner()
        case t: Defn.Val =>
          addPats(
            t.pats,
            SymbolKind.Constant,
            t.pos,
            t.decltpe.fold("")(_.syntax),
          )
          newOwner()
        case t: Decl.Val =>
          addPats(
            t.pats,
            SymbolKind.Constant,
            t.pos,
            t.decltpe.syntax,
          )
          newOwner()
        case t: Defn.Var =>
          addPats(
            t.pats,
            SymbolKind.Variable,
            t.pos,
            t.decltpe.fold("")(_.syntax),
          )
          newOwner()
        case t: Decl.Var =>
          addPats(
            t.pats,
            SymbolKind.Variable,
            t.pos,
            t.decltpe.syntax,
          )
          newOwner()
        case t: Defn.Type =>
          addChild(
            t.name.value,
            SymbolKind.TypeParameter,
            t.pos,
            t.name.pos,
            t.body.syntax,
          )
        case t: Decl.Type =>
          addChild(
            t.name.value,
            SymbolKind.TypeParameter,
            t.pos,
            t.name.pos,
            "",
          )
        case t: Defn.Enum =>
          addChild(
            t.name.value,
            SymbolKind.Enum,
            t.pos,
            t.name.pos,
            "",
          )
          newOwner()
        case t: Defn.RepeatedEnumCase =>
          t.cases.foreach { name =>
            addChild(
              name.value,
              SymbolKind.EnumMember,
              name.pos,
              name.pos,
              "",
            )
          }
          newOwner()
        case t: Defn.EnumCase =>
          addChild(
            t.name.value,
            SymbolKind.EnumMember,
            t.pos,
            t.name.pos,
            "",
          )
        case t: Defn.ExtensionGroup =>
          val declaredType = for {
            eparam <- t.paramss.find(
              _.headOption.exists(!_.mods.contains(Mod.Using()))
            )
            param <- eparam.headOption
            tpe <- param.decltpe
          } yield (tpe.syntax, tpe.pos)
          val (name, pos) = declaredType.getOrElse(("", t.pos))
          addChild(
            s"extension $name",
            SymbolKind.Module,
            t.pos,
            pos,
            name,
          )
          newOwner()
        case t: Defn.GivenAlias =>
          addChild(
            t.name.value,
            SymbolKind.Constant,
            t.pos,
            t.name.pos,
            t.decltpe.syntax,
          )
          newOwner()
        case t: Defn.Given =>
          val (tpeName, initPos) =
            t.templ.inits match {
              case Nil => ("(anonymous)", t.pos)
              case inits =>
                (inits.map(_.tpe.syntax).mkString(" with "), inits.head.pos)
            }

          val (name, pos) =
            t.name match {
              case Name.Anonymous() => (" ", initPos)
              case name => (name.value, name.pos)
            }
          addChild(
            name,
            SymbolKind.Class,
            t.pos,
            pos,
            tpeName,
          )
          newOwner()
        case t: Decl.Given =>
          addChild(
            t.name.value,
            SymbolKind.Constant,
            t.pos,
            t.name.pos,
            t.decltpe.syntax,
          )
        case _ =>
      }
    }
  }
}
