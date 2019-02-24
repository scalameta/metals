package scala.meta.internal.pc

import java.util
import java.util.logging.Logger
import scala.language.implicitConversions
import scala.meta.internal.semanticdb.scalac.SemanticdbOps
import scala.meta.pc.SymbolDocumentation
import scala.meta.pc.SymbolSearch
import scala.reflect.internal.{Flags => gf}
import scala.tools.nsc.Mode
import scala.tools.nsc.Settings
import scala.tools.nsc.interactive.Global
import scala.tools.nsc.interactive.InteractiveAnalyzer
import scala.tools.nsc.reporters.Reporter

class MetalsGlobal(
    settings: Settings,
    reporter: Reporter,
    val search: SymbolSearch,
    val buildTargetIdentifier: String,
    val logger: Logger
) extends Global(settings, reporter)
    with Completions
    with Signatures { compiler =>

  override lazy val analyzer = new {
    val global: compiler.type = compiler
  } with InteractiveAnalyzer {

    /**
     * Disable blackbox macro expansion for better reliability and performance.
     *
     * The benefits of disabling blackbox macros are
     * - we insure ourselves from misbehaving macro library that mess up with compiler APIs
     * - we avoid potentially expensive computation during macro expansion
     * It's safe to disable blackbox macros because they don't affect typing, meaning
     * they cannot change the results from completions/signatureHelp/hover.
     *
     * Here are basic benchmark numbers running completions in Exprs.scala from fastparse,
     * a 150 line source file where a scope completion triggers 80 macros.
     * {{{
     *   // expand 80 blackbox like normal
     *   [info] CachedSearchAndCompilerCompletionBench.complete  scopeFastparse    ss   60  118.484 ± 56.395  ms/op
     *   // disable all blackbox macros
     *   [info] CachedSearchAndCompilerCompletionBench.complete  scopeFastparse    ss   60  60.489 ± 9.197  ms/op
     * }}}
     *
     * We don't use `analyser.addMacroPlugin()` to disable blackbox macros because benchmarks show that
     * macro plugins add ~10ms overhead compared to overriding this method directly.
     */
    override def pluginsMacroExpand(
        typer: Typer,
        expandee: Tree,
        mode: Mode,
        pt: Type
    ): Tree = {
      if (standardIsBlackbox(expandee.symbol)) expandee
      else super.pluginsMacroExpand(typer, expandee, mode, pt)
    }

  }

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

  def symbolDocumentation(symbol: Symbol): Option[SymbolDocumentation] = {
    val sym = compiler.semanticdbSymbol(
      if (!symbol.isJava && symbol.isPrimaryConstructor) symbol.owner
      else symbol
    )
    val documentation = search.documentation(sym)
    if (documentation.isPresent) Some(documentation.get())
    else None
  }

  /**
   * Shortens fully qualified package prefixes to make type signatures easier to read.
   *
   * It becomes difficult to read method signatures when they have a large number of parameters
   * with fully qualified names. This method strips out package prefixes to shorten the names while
   * making sure to not convert two different symbols into same short name.
   */
  def shortType(longType: Type, history: ShortenedNames): Type = {
    def loop(tpe: Type, name: Option[Name]): Type = tpe match {
      case TypeRef(pre, sym, args) =>
        if (sym.isAliasType &&
          (sym.isAbstract || sym.overrides.lastOption.exists(_.isAbstract))) {
          // Always dealias abstract type aliases but leave concrete aliases alone.
          // trait Generic { type Repr /* dealias */ }
          // type Catcher[T] = PartialFunction[Throwable, T] // no dealias
          loop(tpe.dealias, name)
        } else {
          TypeRef(
            loop(pre, Some(sym.name)),
            sym,
            args.map(arg => loop(arg, None))
          )
        }
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
      case MethodType(params, resultType) =>
        MethodType(params, loop(resultType, None))
      case t => t
    }
    longType match {
      case ThisType(_) => longType
      case _ => loop(longType, None)
    }
  }

  /**
   * Custom `Type.toLongString` that shortens fully qualified package prefixes.
   */
  def metalsToLongString(tpe: Type, history: ShortenedNames): String = {
    shortType(tpe, history).toLongString
  }

  /**
   * Converts a SemanticDB symbol into a compiler symbol.
   */
  def inverseSemanticdbSymbols(symbol: String): List[Symbol] = {
    import scala.meta.internal.semanticdb.Scala._
    if (!symbol.isGlobal) return Nil
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
                  val member = owner.info.decl(TypeName(value)) :: Nil
                  if (sym.isJava) owner.info.decl(TermName(value)) :: member
                  else member
                case Descriptor.Term(value) =>
                  owner.info.decl(TermName(value)) :: Nil
                case Descriptor.Package(value) =>
                  owner.info.decl(TermName(value)) :: Nil
                case Descriptor.Parameter(value) =>
                  owner.paramss.flatten.filter(_.name.containsName(value))
                case Descriptor.TypeParameter(value) =>
                  owner.typeParams.filter(_.name.containsName(value))
                case Descriptor.Method(value, _) =>
                  owner.info
                    .decl(TermName(value))
                    .alternatives
                    .iterator
                    .filter(sym => semanticdbSymbol(sym) == s)
                    .toList
              }
          }
        parentSymbol.flatMap(tryMember)
      }
    }
    loop(symbol).filterNot(_ == NoSymbol)
  }

  def inverseSemanticdbSymbol(symbol: String): Symbol = {
    inverseSemanticdbSymbols(symbol) match {
      case head :: _ =>
        head
      case Nil =>
        NoSymbol
    }
  }

  // NOTE(olafur): see compiler plugin test cases in `CompletionSlowSuite`
  // why this override is necessary. Compiler plugins like kind-projector
  // use `TypingTransformer`, which produces contexts that break completions.
  // We whitelist a set of known compiler phases which `addContext` was designed
  // to work with.
  override def addContext(contexts: Contexts, context: Context): Unit = {
    phase.name match {
      case "typer" | "namer" =>
        super.addContext(contexts, context)
      case _ =>
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
    unitOfFile.get(richUnit.source.file) match {
      case Some(value)
          if util.Arrays.equals(
            value.source.content,
            richUnit.source.content
          ) =>
        value
      case _ =>
        unitOfFile(richUnit.source.file) = richUnit
        richUnit
    }
  }

  // Needed for 2.11 where `Name` doesn't extend CharSequence.
  implicit def nameToCharSequence(name: Name): CharSequence =
    name.toString

}
