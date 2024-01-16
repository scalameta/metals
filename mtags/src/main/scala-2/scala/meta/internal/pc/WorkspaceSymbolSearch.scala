package scala.meta.internal.pc

import java.nio.file.Path

import scala.util.control.NonFatal

import scala.meta.pc.SymbolSearchVisitor

import org.eclipse.{lsp4j => l}

trait WorkspaceSymbolSearch { this: MetalsGlobal =>

  def info(symbol: String): List[PcSymbolInformation] = {
    val index = symbol.lastIndexOf("/")
    val pkgString = symbol.take(index + 1)
    val pkg = packageSymbolFromString(pkgString)

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
      loop(symbol.drop(index + 1).takeWhile(_ != '('), List.empty)

    val compilerSymbols = names.foldLeft(pkg.toList) {
      case (owners, (name, isClass)) =>
        owners.flatMap { owner =>
          val foundChild =
            if (isClass) owner.info.member(TypeName(name))
            else owner.info.member(TermName(name))
          if (foundChild.exists) {
            foundChild.info match {
              case OverloadedType(_, alts) => alts
              case _ => List(foundChild)
            }
          } else Nil
        }
    }

    compilerSymbols.map(compilerSymbol =>
      PcSymbolInformation(
        symbol = semanticdbSymbol(compilerSymbol),
        kind = getSymbolKind(compilerSymbol),
        parents = compilerSymbol.parentSymbols.map(semanticdbSymbol),
        dealisedSymbol = semanticdbSymbol(compilerSymbol.dealiased),
        classOwner = compilerSymbol.ownerChain
          .find(c => c.isClass || c.isModule)
          .map(semanticdbSymbol),
        overridden = compilerSymbol.overrides.map(semanticdbSymbol),
        properties =
          if (compilerSymbol.isAbstractClass || compilerSymbol.isAbstractType)
            List(PcSymbolProperty.ABSTRACT)
          else Nil
      )
    )
  }

  private def getSymbolKind(sym: Symbol): PcSymbolKind.PcSymbolKind =
    if (sym.isJavaInterface) PcSymbolKind.INTERFACE
    else if (sym.isTrait) PcSymbolKind.TRAIT
    else if (sym.isConstructor) PcSymbolKind.CONSTRUCTOR
    else if (sym.isPackageObject) PcSymbolKind.PACKAGE_OBJECT
    else if (sym.isClass) PcSymbolKind.CLASS
    else if (sym.isMacro) PcSymbolKind.MACRO
    else if (sym.isLocalToBlock) PcSymbolKind.LOCAL
    else if (sym.isMethod) PcSymbolKind.METHOD
    else if (sym.isParameter) PcSymbolKind.PARAMETER
    else if (sym.hasPackageFlag) PcSymbolKind.PACKAGE
    else if (sym.isTypeParameter) PcSymbolKind.TYPE_PARAMETER
    else if (sym.isType) PcSymbolKind.TYPE
    else PcSymbolKind.UNKNOWN_KIND

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
