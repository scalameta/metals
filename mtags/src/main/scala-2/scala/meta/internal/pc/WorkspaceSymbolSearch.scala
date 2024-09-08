package scala.meta.internal.pc

import java.nio.file.Path

import scala.annotation.tailrec
import scala.collection.mutable
import scala.reflect.NameTransformer
import scala.util.control.NonFatal

import scala.meta.internal.mtags.MtagsEnrichments._
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
        val allParents = {
          val visited = mutable.Set[Symbol]()
          def collect(sym: Symbol): Unit = {
            visited += sym
            sym.parentSymbols.foreach {
              case parent if !visited(parent) => collect(parent)
              case _ =>
            }
          }
          collect(compilerSymbol)
          visited.toList.map(semanticdbSymbol)
        }
        val defnAnn =
          compilerSymbol.info.members.filter(_.isMethod).flatMap(_.annotations)
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
              else Nil,
            allParents,
            compilerSymbol.annotations.map(_.toString()).distinct,
            defnAnn.map(_.toString()).toList.distinct
          )
        )
      case _ => None
    }
  }

  def compilerSymbol(symbol: String): Option[Symbol] =
    compilerSymbols(symbol).find(sym => semanticdbSymbol(sym) == symbol)

  private def compilerSymbols(symbol: String) = {
    val info = SymbolInfo.getPartsFromSymbol(symbol)
    val pkg = packageSymbolFromString(info.packagePart)
    val symbols = info.names.foldLeft(pkg.toList) {
      case (owners, (nameStr, isClass)) =>
        owners.flatMap { owner =>
          val encoded = NameTransformer.encode(nameStr.stripBackticks)
          val name =
            if (encoded == nme.CONSTRUCTOR.encoded) nme.CONSTRUCTOR
            else if (isClass) TypeName(encoded)
            else TermName(encoded)

          val foundChild = owner.info.member(name)
          if (foundChild.exists) {
            foundChild.info match {
              case OverloadedType(_, alts) => alts
              case _ => List(foundChild)
            }
          } else Nil
        }
    }

    info.paramName match {
      case Some(name) =>
        symbols.flatMap(_.paramss.flatten.find(_.name.decoded == name))
      case _ => symbols
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
        sym <- loadSymbolFromClassfile(top, context)
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
      classfile: SymbolSearchCandidate,
      context: Context
  ): List[Symbol] = {
    def isAccessible(sym: Symbol): Boolean = {
      context.isAccessible(sym, sym.info)
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
