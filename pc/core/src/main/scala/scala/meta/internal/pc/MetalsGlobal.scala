package scala.meta.internal.pc

import java.util.logging.Logger
import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.language.implicitConversions
import scala.meta.internal.semanticdb.scalac.SemanticdbOps
import scala.meta.pc
import scala.meta.pc.SymbolDocumentation
import scala.meta.pc.SymbolSearch
import scala.reflect.internal.{Flags => gf}
import scala.tools.nsc.Settings
import scala.tools.nsc.interactive.Global
import scala.tools.nsc.reporters.Reporter

class MetalsGlobal(
    settings: Settings,
    reporter: Reporter,
    val search: SymbolSearch,
    val buildTargetIdentifier: String,
    val logger: Logger
) extends Global(settings, reporter) { compiler =>

  def isDocs: Boolean = System.getProperty("metals.signature-help") != "no-docs"
  def isJavaSymbol(sym: Symbol): Boolean =
    !sym.hasPackageFlag && sym.isJava

  lazy val semanticdbOps: SemanticdbOps {
    val global: compiler.type
  } = new SemanticdbOps {
    val global: compiler.type = compiler
  }

  def semanticdbSymbol(symbol: Symbol): String = {
    import semanticdbOps._
    symbol.toSemantic
  }

  def printPretty(pos: Position): Unit = {
    println(pretty(pos))
  }
  def pretty(pos: Position): String = {
    if (pos.isDefined) {
      val lineCaret =
        if (pos.isRange) {
          val indent = " " * (pos.column - 1)
          val caret = "^" * (pos.end - pos.start)
          indent + caret
        } else {
          pos.lineCaret
        }
      pos.lineContent + "\n" + lineCaret
    } else {
      "<none>"
    }
  }

  def treePos(tree: Tree): Position = {
    if (tree.pos == null) {
      NoPosition
    } else if (tree.symbol != null &&
      tree.symbol.name.startsWith("x$") &&
      tree.symbol.isArtifact) {
      tree.symbol.pos
    } else {
      tree.pos
    }
  }

  def methodInfoSymbol(symbol: Symbol): Symbol =
    if (!symbol.isJava && symbol.isPrimaryConstructor) symbol.owner
    else symbol
  def rawMethodInfo(symbol: Symbol): Option[SymbolDocumentation] = {
    for {
      info <- methodInfos.get(semanticdbSymbol(methodInfoSymbol(symbol)))
      if info != null
    } yield info
  }
  def methodInfo(symbol: Symbol): Option[SymbolDocumentation] = {
    val sym = compiler.semanticdbSymbol(methodInfoSymbol(symbol))
    val documentation = search.documentation(sym)
    if (documentation.isPresent) Some(documentation.get())
    else None
  }

  // The following pattern match is an adaptation of this pattern match:
  // https://github.com/scalameta/scalameta/blob/dc639c83f1c26627c39aef9bfb3dae779ecdb237/semanticdb/scalac/library/src/main/scala/scala/meta/internal/semanticdb/scalac/TypeOps.scala
  def shortType(longType: Type, history: ShortenedNames): Type = {
    def loop(tpe: Type, name: Option[Name]): Type = tpe match {
      case TypeRef(pre, sym, args) =>
        TypeRef(
          loop(pre, Some(sym.name)),
          sym,
          args.map(arg => loop(arg, name))
        )
      case SingleType(pre, sym) =>
        if (sym.hasPackageFlag) {
          if (history.tryShortenName(name, sym)) NoPrefix
          else tpe
        } else {
          SingleType(loop(pre, Some(sym.name)), sym)
        }
      case ThisType(sym) =>
        if (sym.hasPackageFlag) {
          if (history.tryShortenName(name, sym)) NoPrefix
          else tpe
        } else {
          TypeRef(NoPrefix, sym, Nil)
        }
      case ConstantType(Constant(sym: TermSymbol))
          if sym.hasFlag(gf.JAVA_ENUM) =>
        loop(SingleType(sym.owner.thisPrefix, sym), None)
      case ConstantType(Constant(tpe: Type)) =>
        ConstantType(Constant(loop(tpe, None)))
      case SuperType(thistpe, supertpe) =>
        SuperType(loop(thistpe, None), loop(supertpe, None))
      case RefinedType(parents, decls) =>
        RefinedType(parents.map(parent => loop(parent, None)), decls)
      case AnnotatedType(annotations, underlying) =>
        AnnotatedType(annotations, loop(underlying, None))
      case ExistentialType(quantified, underlying) =>
        ExistentialType(quantified, loop(underlying, None))
      case PolyType(tparams, resultType) =>
        PolyType(tparams, resultType.map(t => loop(t, None)))
      case NullaryMethodType(resultType) =>
        loop(resultType, None)
      case TypeBounds(lo, hi) =>
        TypeBounds(loop(lo, None), loop(hi, None))
      case t => t
    }
    longType match {
      case ThisType(_) => longType
      case _ => loop(longType, None)
    }
  }

  def metalsToLongString(tpe: Type, history: ShortenedNames): String = {
    shortType(tpe, history).toLongString
  }

//  def metalsToShortString(tpe: Type): String = {
//    val sb = new StringBuilder()
//    def loop(t: Type): Unit = {}
//    sb.toString()
//  }

  val methodInfos = TrieMap.empty[String, SymbolDocumentation]

  // Only needed for 2.11 where `Name` doesn't extend CharSequence.
  implicit def nameToCharSequence(name: Name): CharSequence =
    name.toString

  class ShortenedNames(history: mutable.Map[Name, Symbol] = mutable.Map.empty) {
    def tryShortenName(name: Option[Name], sym: Symbol): Boolean =
      name match {
        case Some(n) =>
          history.get(n) match {
            case Some(other) =>
              if (other == sym) true
              else false
            case _ =>
              history(n) = sym
              true
          }
        case _ =>
          false
      }
  }

  def inverseSemanticdbSymbol(symbol: String): Symbol = {
    import scala.meta.internal.semanticdb.Scala._
    if (!symbol.isGlobal) return NoSymbol
    def loop(s: String): List[Symbol] = {
      if (s.isNone || s.isRootPackage) rootMirror.RootPackage :: Nil
      else if (s.isEmptyPackage) rootMirror.EmptyPackage :: Nil
      else {
        val (desc, parent) = DescriptorParser(s)
        val parentSymbol = loop(parent)
        def tryMember(sym: Symbol): List[Symbol] =
          sym match {
            case NoSymbol =>
              Nil
            case owner =>
              desc match {
                case Descriptor.None =>
                  Nil
                case Descriptor.Type(value) =>
                  val member = owner.info.member(TypeName(value)) :: Nil
                  if (sym.isJava) owner.info.member(TermName(value)) :: member
                  else member
                case Descriptor.Term(value) =>
                  owner.info.member(TermName(value)) :: Nil
                case Descriptor.Package(value) =>
                  owner.info.member(TermName(value)) :: Nil
                case Descriptor.Parameter(value) =>
                  owner.paramss.flatten.filter(_.name.containsName(value))
                case Descriptor.TypeParameter(value) =>
                  owner.typeParams.filter(_.name.containsName(value))
                case Descriptor.Method(value, _) =>
                  owner.info
                    .member(TermName(value))
                    .alternatives
                    .iterator
                    .filter(sym => semanticdbSymbol(sym) == s)
                    .toList
              }
          }
        parentSymbol.flatMap(tryMember)
      }
    }
    loop(symbol) match {
      case head :: _ =>
        head
      case Nil =>
        NoSymbol
    }
  }

  class SignaturePrinter(
      gsym: Symbol,
      shortenedNames: ShortenedNames,
      gtpe: Type,
      includeDocs: Boolean
  ) {
    private val info =
      if (includeDocs || (gsym.isMethod && isJavaSymbol(gsym))) {
        methodInfo(gsym)
      } else {
        rawMethodInfo(gsym)
      }
    private val infoParamsA: Seq[pc.SymbolDocumentation] = info match {
      case Some(value) =>
        value.typeParameters().asScala ++
          value.parameters().asScala
      case None =>
        IndexedSeq.empty
    }
    private val infoParams =
      infoParamsA.lift
    private val returnType =
      metalsToLongString(gtpe.finalResultType, shortenedNames)

    def methodDocstring: String = {
      if (isDocs) info.fold("")(_.docstring())
      else ""
    }
    def isTypeParameters: Boolean = gtpe.typeParams.nonEmpty
    def isImplicit: Boolean = gtpe.paramss.lastOption match {
      case Some(head :: _) => head.isImplicit
      case _ => false
    }
    def mparamss: List[List[Symbol]] =
      gtpe.typeParams match {
        case Nil => gtpe.paramss
        case tparams => tparams :: gtpe.paramss
      }
    def defaultMethodSignature: String = {
      var i = 0
      val paramss = gtpe.typeParams match {
        case Nil => gtpe.paramss
        case tparams => tparams :: gtpe.paramss
      }
      val params = paramss.iterator.map { params =>
        val labels = params.iterator.map { param =>
          val result = paramLabel(param, i)
          i += 1
          result
        }
        labels
      }
      methodSignature(params, name = "")
    }

    def methodSignature(
        paramLabels: Iterator[Iterator[String]],
        name: String = gsym.nameString
    ): String = {
      paramLabels
        .zip(mparamss.iterator)
        .map {
          case (params, syms) =>
            paramsKind(syms) match {
              case Params.TypeParameterKind =>
                params.mkString("[", ", ", "]")
              case Params.NormalKind =>
                params.mkString("(", ", ", ")")
              case Params.ImplicitKind =>
                params.mkString("(implicit ", ", ", ")")
            }
        }
        .mkString(name, "", s": ${returnType}")
    }
    def paramsKind(syms: List[Symbol]): Params.Kind = {
      syms match {
        case head :: _ =>
          if (head.isType) Params.TypeParameterKind
          else if (head.isImplicit) Params.ImplicitKind
          else Params.NormalKind
        case Nil => Params.NormalKind
      }
    }
    def paramDocstring(paramIndex: Int): String = {
      if (isDocs) infoParams(paramIndex).fold("")(_.docstring())
      else ""
    }
    def paramLabel(param: Symbol, index: Int): String = {
      val paramTypeString = metalsToLongString(param.info, shortenedNames)
      val name = infoParams(index) match {
        case Some(value) => value.name()
        case None => param.nameString
      }
      if (param.isTypeParameter) {
        name + paramTypeString
      } else {
        val default =
          if (param.isParamWithDefault) {
            val defaultValue = infoParams(index).map(_.defaultValue()) match {
              case Some(value) if !value.isEmpty => value
              case _ => "{}"
            }
            s" = $defaultValue"
          } else {
            ""
          }
        s"$name: ${paramTypeString}$default"
      }
    }
  }

  def addCompilationUnit(
      code: String,
      filename: String,
      cursor: Option[Int],
      cursorName: String = "_CURSOR_"
  ): RichCompilationUnit = {
    val codeWithCursor = cursor match {
      case Some(offset) =>
        code.take(offset) + cursorName + code.drop(offset)
      case _ => code
    }
    val unit = newCompilationUnit(codeWithCursor, filename)
    val richUnit = new RichCompilationUnit(unit.source)
    unitOfFile(richUnit.source.file) = richUnit
    richUnit
  }

}
