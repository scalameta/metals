package scala.meta.internal.pc

import scala.meta.pc.SymbolSearchVisitor
import java.nio.file.Path
import org.eclipse.{lsp4j => l}
import scala.util.control.NonFatal

trait WorkspaceSymbolSearch { this: MetalsGlobal =>

  class CompilerSearchVisitor(
      query: String,
      context: Context,
      visitMember: Symbol => Boolean
  ) extends SymbolSearchVisitor {
    def visit(top: SymbolSearchCandidate): Int = {
      var added = 0
      for {
        sym <- loadSymbolFromClassfile(top)
        if context.lookupSymbol(sym.name, _ => true).symbol != sym
      } {
        if (visitMember(sym)) {
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

    override def isCancelled: Boolean = {
      false
    }
  }

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
