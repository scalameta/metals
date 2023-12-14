package scala.meta.internal.pc

import java.nio.file.Path

import scala.util.control.NonFatal

import scala.meta.pc.SymbolSearchVisitor

import org.eclipse.{lsp4j => l}

trait WorkspaceSymbolSearch { this: MetalsGlobal =>

  def findParents(symbol: String): List[String] = {
    val index = symbol.lastIndexOf("/")
    val pkgString = symbol.take(index + 1)
    val pkg = packageSymbolFromString(pkgString).getOrElse(
      throw new NoSuchElementException(pkgString)
    )

    def loop(
        symbol: String,
        acc: List[(String, Boolean)]
    ): List[(String, Boolean)] =
      if (symbol.isEmpty()) acc.reverse
      else {
        val newSymbol = symbol.takeWhile(c => c != '.' && c != '#')
        val rest = symbol.drop(newSymbol.size)
        loop(rest.drop(1), (newSymbol, rest.headOption.exists(_ == '#')) :: acc)
      }
    val names =
      loop(symbol.drop(index + 1), List.empty)
    val compilerSymbol = names.foldLeft(pkg) { case (sym, (name, isClass)) =>
      if (isClass) sym.info.member(TypeName(name))
      else sym.info.member(TermName(name))
    }

    compilerSymbol.parentSymbols.map(semanticdbSymbol)
  }

  class CompilerSearchVisitor(
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
