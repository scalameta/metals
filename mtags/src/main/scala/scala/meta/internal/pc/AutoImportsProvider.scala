package scala.meta.internal.pc

import scala.meta.pc.OffsetParams
import org.eclipse.{lsp4j => l}
import scala.meta.pc.SymbolSearchVisitor
import java.nio.file.Path
import scala.meta.pc.AutoImportsResult
import scala.collection.mutable
import scala.collection.JavaConverters._
import scala.util.control.NonFatal

final class AutoImportsProvider(
    val compiler: MetalsGlobal,
    name: String,
    params: OffsetParams
) {
  import compiler._

  def autoImports(): List[AutoImportsResult] = {
    val unit = addCompilationUnit(
      code = params.text,
      filename = params.filename,
      cursor = Some(params.offset)
    )
    val pos = unit.position(params.offset)
    val importPosition = autoImportPosition(pos, params.text())
    val context = doLocateImportContext(pos, importPosition)
    val isSeen = mutable.Set.empty[String]
    val symbols = List.newBuilder[Symbol]

    def visit(sym: Symbol): Boolean = {
      val id = sym.fullName
      if (!isSeen(id)) {
        isSeen += id
        symbols += sym
        true
      }
      false
    }

    val visitor = new CompilerSearchVisitor(name, context, visit)

    search.search(name, buildTargetIdentifier, visitor)

    symbols.result.collect {
      case sym if sym.name.dropLocal.decoded == name =>
        val ident = Identifier.backtickWrap(sym.name.dropLocal.decoded)
        val pkg = sym.owner.fullName
        val edits = importPosition match {
          case None => Nil
          case Some(value) =>
            val (short, edits) = ShortenedNames.synthesize(
              TypeRef(ThisType(sym.owner), sym, Nil),
              pos,
              context,
              value
            )
            edits
        }
        AutoImportsResultImpl(pkg, edits.asJava)
    }
  }

  private class CompilerSearchVisitor(
      query: String,
      context: Context,
      visitSymbol: Symbol => Boolean
  ) extends SymbolSearchVisitor {

    def visit(top: SymbolSearchCandidate): Int = {
      var added = 0
      for {
        sym <- loadSymbolFromClassfile(top)
        if context.lookupSymbol(sym.name, _ => true).symbol != sym
      } {
        if (visitSymbol(sym)) {
          added += 1
        }
      }
      added
    }

    def visitClassfile(pkg: String, filename: String): Int = {
      visit(SymbolSearchCandidate.Classfile(pkg, filename))
    }

    def visitWorkspaceSymbol(
        path: Path,
        symbol: String,
        kind: l.SymbolKind,
        range: l.Range
    ): Int = {
      visit(SymbolSearchCandidate.Workspace(symbol))
    }

    def shouldVisitPackage(pkg: String): Boolean =
      packageSymbolFromString(pkg).isDefined

    override def isCancelled: Boolean =
      false

  }

  // FIXME(gabro): this is copy-pasted from CompletionProvider, we should de-dupe it
  private def loadSymbolFromClassfile(
      classfile: SymbolSearchCandidate
  ): List[Symbol] = {
    def isAccessible(sym: Symbol): Boolean = {
      sym != NoSymbol && {
        sym.info // needed to fill complete symbol
        sym.isPublic
      }
    }
    try {
      classfile match {
        case SymbolSearchCandidate.Classfile(pkgString, filename) =>
          val pkg = packageSymbolFromString(pkgString).getOrElse(
            throw new NoSuchElementException(pkgString)
          )
          val names = filename
            .stripSuffix(".class")
            .split('$')
            .iterator
            .filterNot(_.isEmpty)
            .toList
          val members = names.foldLeft(List[Symbol](pkg)) {
            case (accum, name) =>
              accum.flatMap { sym =>
                if (!isAccessible(sym) || !sym.isModuleOrModuleClass) Nil
                else {
                  sym.info.member(TermName(name)) ::
                    sym.info.member(TypeName(name)) ::
                    Nil
                }
              }
          }
          members.filter(sym => isAccessible(sym))
        case SymbolSearchCandidate.Workspace(symbol) =>
          val gsym = inverseSemanticdbSymbol(symbol)
          if (isAccessible(gsym)) gsym :: Nil
          else Nil
      }
    } catch {
      case NonFatal(_) => Nil
    }
  }

}
