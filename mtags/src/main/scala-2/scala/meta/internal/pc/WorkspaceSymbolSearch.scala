package scala.meta.internal.pc

import java.nio.file.Path

import scala.annotation.tailrec
import scala.util.control.NonFatal

import scala.meta.pc.PcSymbolKind
import scala.meta.pc.PcSymbolProperty
import scala.meta.pc.SymbolSearchVisitor

import org.eclipse.{lsp4j => l}

trait WorkspaceSymbolSearch { compiler: MetalsGlobal =>

  def searchOutline(
      visitMember: Symbol => Boolean,
      query: String
  ): Unit = {

    def traverseUnit(unit: RichCompilationUnit) = {
      @tailrec
      def loop(trees: List[Tree]): Unit = {
        trees match {
          case Nil =>
          case (tree: MemberDef) :: tail =>
            val sym = tree.symbol
            def matches = if (sym.isType)
              CompletionFuzzy.matchesSubCharacters(query, sym.name.toString())
            else CompletionFuzzy.matches(query, sym.name.toString())
            if (sym != null && sym.exists && matches) {
              try {
                visitMember(sym)
              } catch {
                case _: Throwable =>
                // with outline compiler there might be situations when things fail
              }
            }
            loop(tree.children ++ tail)
          case tree :: tail =>
            loop(tree.children ++ tail)
        }
      }
      loop(List(unit.body))
    }
    compiler.richCompilationCache.values.foreach(traverseUnit)
  }

  def info(symbol: String): Option[PcSymbolInformation] = {
    val (searchedSymbol, alternativeSymbols) =
      compilerSymbols(symbol).partition(compilerSymbol =>
        semanticdbSymbol(compilerSymbol) == symbol
      )

    searchedSymbol match {
      case compilerSymbol :: _ =>
        Some(
          PcSymbolInformation(
            symbol = symbol,
            kind = getSymbolKind(compilerSymbol),
            parents = compilerSymbol.parentSymbols.map(semanticdbSymbol),
            dealiasedSymbol = semanticdbSymbol(compilerSymbol.dealiased),
            classOwner = compilerSymbol.ownerChain
              .find(c => c.isClass || c.isModule)
              .map(semanticdbSymbol),
            overriddenSymbols = compilerSymbol.overrides.map(semanticdbSymbol),
            alternativeSymbols = alternativeSymbols.map(semanticdbSymbol),
            properties =
              if (
                compilerSymbol.isAbstractClass || compilerSymbol.isAbstractType
              )
                List(PcSymbolProperty.ABSTRACT)
              else Nil
          )
        )
      case _ => None
    }
  }

  def compilerSymbol(symbol: String): Option[Symbol] =
    compilerSymbols(symbol).find(sym => semanticdbSymbol(sym) == symbol)

  private def compilerSymbols(symbol: String) = {
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

    names.foldLeft(pkg.toList) { case (owners, (name, isClass)) =>
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
  }

  private def getSymbolKind(sym: Symbol): PcSymbolKind =
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
      visit(SymbolSearchCandidate.Workspace(symbol, path))
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
        case SymbolSearchCandidate.Workspace(symbol, path)
            if !compiler.isOutlinedFile(path) =>
          val gsym = inverseSemanticdbSymbol(symbol)
          if (isAccessible(gsym)) gsym :: Nil
          else Nil
        case _ =>
          Nil
      }
    } catch {
      case NonFatal(_) => Nil
    }
  }
}
