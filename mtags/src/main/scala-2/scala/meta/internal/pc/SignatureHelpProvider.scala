package scala.meta.internal.pc

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.metals.PcQueryContext
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.pc.OffsetParams

import org.eclipse.lsp4j.ParameterInformation
import org.eclipse.lsp4j.SignatureHelp
import org.eclipse.lsp4j.SignatureInformation

class SignatureHelpProvider(val compiler: MetalsGlobal)(implicit
    queryInfo: PcQueryContext
) {
  import compiler._

  def signatureHelp(
      params: OffsetParams
  ): SignatureHelp = {
    val unit = addCompilationUnit(
      code = params.text(),
      filename = params.uri().toString(),
      cursor = cursor(params.offset(), params.text())
    )
    val pos = unit.position(params.offset())
    (for {
      _ <- safeTypedTreeAt(pos)
      enclosingApply = new EnclosingApply(pos).find(unit.body)
      typedEnclosing <- safeTypedTreeAt(enclosingApply.pos)
      enclosingCall <- new MethodCallTraverser(unit, pos).fromTree(
        typedEnclosing
      )
    } yield toSignatureHelp(enclosingCall)) getOrElse new SignatureHelp()
  }

  private def safeTypedTreeAt(pos: Position): Option[Tree] =
    try {
      Some(typedTreeAt(pos))
    } catch {
      case _: NullPointerException => None
    }

  class EnclosingApply(pos: Position) extends Traverser {
    var last: Tree = EmptyTree
    def find(tree: Tree): Tree = {
      traverse(tree)
      last
    }
    def isValidQualifier(qual: Tree): Boolean =
      !qual.pos.includes(pos) && qual.pos.isRange && (qual match {
        // Ignore synthetic TupleN constructors from tuple syntax.
        case Select(ident @ Ident(TermName("scala")), TermName(tuple))
            if tuple.startsWith("Tuple") && ident.pos == qual.pos =>
          false
        case _ =>
          true
      })
    override def traverse(tree: Tree): Unit = {

      // Position of annotation tree is outside of `tree.pos` so must be checked separately
      val annotationTrees = tree match {
        case annotatable: MemberDef => annotatable.mods.annotations
        case _ => Nil
      }

      (tree :: annotationTrees).find(_.pos.includes(pos)) match {
        case Some(found) =>
          found match {
            case Apply(qual, _) if isValidQualifier(qual) =>
              last = found
            case TypeApply(qual, _) if isValidQualifier(qual) =>
              last = found
            case AppliedTypeTree(qual, _) if isValidQualifier(qual) =>
              last = found
            case _ =>
          }
          super.traverse(found)

        case None =>
      }
    }
  }

  case class Arg(
      tree: Tree,
      paramsIndex: Int,
      paramIndex: Int
  ) {
    def matches(param: Symbol, i: Int, j: Int): Boolean =
      paramsIndex == i && {
        paramIndex == j ||
        (param.tpe != null && paramIndex > j &&
          definitions.isRepeatedParamType(param.tpe))
      }
  }

  // A method call like `function[A, B](a, b)(c, d)`
  case class MethodCall(
      tree: Tree,
      qual: Tree,
      symbol: Symbol,
      tparams: List[Tree],
      argss: List[List[Tree]]
  ) {
    private def nonOverloadInfo(info: Type): Type = {
      info match {
        case OverloadedType(_, head :: _) => head.info
        case PolyType(tparam, resultType) =>
          PolyType(tparam, nonOverloadInfo(resultType))
        case tpe => tpe
      }
    }
    val isUnapplyMethod: Boolean = tree match {
      case _: UnApply => true
      // in some case we will get an Apply tree, but in that case position indicates unapply
      case _ =>
        symbol.name == termNames.unapply && (symbol.pos == NoPosition || !symbol.pos.isRange)
    }
    val qualTpe: Type = symbol.name match {
      case termNames.unapply =>
        symbol.paramLists match {
          case (head :: Nil) :: Nil =>
            qual.tpe.finalResultType match {
              case tp @ TypeRef(
                    _,
                    cls,
                    tpe @ TypeRef(_, _, args) :: Nil
                  )
                  if cls == definitions.OptionClass || cls == definitions.SomeClass =>
                val ctor = head.tpe.typeSymbol.primaryConstructor
                val params = ctor.paramLists.headOption.getOrElse(Nil)
                val toZip = args match {
                  case Nil => tpe
                  case _ => args
                }
                val isAlignedTypes = toZip.lengthCompare(params.length) == 0 &&
                  toZip.zip(params).forall { case (a, b) =>
                    a == b.tpe ||
                    b.tpe.typeSymbol.isTypeParameter
                  }
                if (isAlignedTypes && ctor.owner.isCaseClass) {
                  tp.memberType(ctor)
                } else {
                  qual.tpe
                }
              case _ =>
                qual.tpe
            }
          case _ =>
            symbol.info
        }
      case termNames.unapplySeq =>
        symbol.info
      case _ =>
        val fromOverload = qual.tpe match {
          case OverloadedType(pre, alts) =>
            val toFind = nonOverload
            pre.memberType(alts.find(_ == toFind).getOrElse(alts.head))
          case tpe => tpe
        }
        nonOverloadInfo(
          if (fromOverload == null) symbol.info
          else fromOverload
        )
    }

    /** Available overloads along with their type as seen from the current method call */
    def alternatives: List[(Symbol, Type)] =
      symbol match {
        case o: ModuleSymbol =>
          o.info
            .member(compiler.nme.apply)
            .safeAlternatives
            .flatMap { alt =>
              val tpe = qual.tpe
              if (tpe == null) None
              else Some(alt -> tpe.memberType(alt))
            }
        case o: ClassSymbol =>
          o.info
            .member(compiler.termNames.CONSTRUCTOR)
            .safeAlternatives
            .map(alt => alt -> alt.tpe)
        case m: MethodSymbol if !m.isLocalToBlock =>
          val recieverTpe = qual match {
            case Select(qualifier, _) if qualifier.tpe ne null => qualifier.tpe
            case _ => m.owner.info
          }
          recieverTpe
            .member(symbol.name)
            .safeAlternatives
            .map(alt => alt -> recieverTpe.memberType(alt))
        case _ =>
          symbol.safeAlternatives.map(alt => alt -> alt.tpe)
      }

    def nonOverload: Symbol =
      if (!symbol.isOverloaded) symbol
      else alternatives.headOption.fold(symbol)(_._1)
    def gparamss: List[List[Symbol]] = {
      if (qualTpe.typeParams.isEmpty) nonOverload.paramLists
      else nonOverload.typeParams :: nonOverload.paramLists
    }
    def all: List[List[Tree]] =
      if (qualTpe.typeParams.isEmpty || tparams.isEmpty) argss
      else tparams :: argss
    def paramTree(i: Int, j: Int): List[Tree] =
      all.lift(i).flatMap(_.lift(j)).toList
    def margss: List[List[Tree]] = {
      all
    }
  }

  object MethodCall {

    /**
     * Returns true if this symbol is `TupleN.apply` constructor.
     */
    def isTupleApply(sym: Symbol): Boolean =
      sym.name == termNames.apply &&
        definitions.isTupleSymbol(sym.owner.companion)

    def unapply(tree: Tree): Option[MethodCall] = {
      tree match {
        case AppliedTypeTree(qual, targs) =>
          Some(MethodCall(tree, qual, treeSymbol(qual), targs, Nil))
        case TypeApply(qual, targs) =>
          Some(MethodCall(tree, qual, treeSymbol(tree), targs, Nil))
        case TreeApply(qual, args) =>
          var tparams: List[Tree] = Nil
          def loop(
              t: Tree,
              paramss: List[List[Symbol]],
              accum: List[List[Tree]]
          ): (Tree, List[List[Tree]]) = {
            (t, paramss) match {
              case (Apply(qual0, args0), _ :: tail) =>
                loop(qual0, tail, args0 :: accum)
              case (TypeApply(qual0, args0), _) =>
                tparams = args0
                (qual0, accum)
              case _ =>
                (t, accum)
            }
          }
          val symbol = treeSymbol(tree)
          for {
            info <- Option(symbol.info)
            if !isTupleApply(symbol)
          } yield {
            val (refQual, argss) = info.paramss match {
              case _ :: tail =>
                loop(qual, tail, args :: Nil)
              case _ =>
                loop(qual, Nil, args :: Nil)
                (qual, args :: Nil)
            }
            MethodCall(tree, refQual, symbol, tparams, argss)
          }
        case _ => None
      }
    }
  }

  // Returns a cursor offset only if the cursor is between two delimiters
  // Insert cursor:
  //  foo(@@)
  //  foo(@@,)
  //  foo(1,@@)
  // Don't insert cursor:
  //  foo(a@@)
  def cursor(offset: Int, text: String): Option[Int] = {
    if (offset >= text.length) return None
    var leadingDelimiter = offset - 1
    while (leadingDelimiter > 0 && text.charAt(leadingDelimiter).isWhitespace) {
      leadingDelimiter -= 1
    }
    if (leadingDelimiter >= 0) {
      text.charAt(leadingDelimiter) match {
        case '(' | '[' | ',' | '>' | '=' =>
          var trailingDelimiter = offset
          while (
            trailingDelimiter < text.length &&
            text.charAt(trailingDelimiter).isWhitespace
          ) {
            trailingDelimiter += 1
          }
          if (trailingDelimiter < text.length) {
            text.charAt(trailingDelimiter) match {
              case ')' | ']' | ',' =>
                Some(offset)
              case _ =>
                None
            }
          } else {
            None
          }

        case _ =>
          None
      }
    } else {
      None
    }
  }

  case class EnclosingMethodCall(
      call: MethodCall,
      activeArg: Arg
  ) {
    def alternatives: List[(Symbol, Type)] = call.alternatives
    def symbol: Symbol = call.symbol
  }

  // A traverser that finds the nearest enclosing method call for a given position.
  class MethodCallTraverser(unit: RichCompilationUnit, pos: Position)
      extends Traverser {
    private var activeCallsite: Option[(MethodCall, Arg)] = None
    def fromTree(body: Tree): Option[EnclosingMethodCall] = {
      traverse(body)
      for {
        (callsite, arg) <- activeCallsite
        if callsite.alternatives.nonEmpty
      } yield EnclosingMethodCall(callsite, arg)
    }

    def toVisit(tree: Tree): Option[Tree] = {
      tree match {
        // Special case: a method call with named arguments like `foo(a = 1, b = 2)` gets desugared into the following:
        // {
        //   val x$1 = 1
        //   val x$2 = 2
        //   foo(x$1, x$2)
        // }
        // In this case, the `foo(x$1, x$2)` has a transparent position, which we don't visit by default, so we
        // make an exception and visit it nevertheless.
        case Block(stats, expr)
            if tree.symbol == null &&
              stats.forall { stat =>
                stat.symbol != null && stat.symbol.isArtifact
              } =>
          Some(expr)
        case _ =>
          if (tree.pos != null && tree.pos.includes(pos)) Some(tree)
          else None
      }
    }
    override def traverse(tree: compiler.Tree): Unit = {
      toVisit(tree) match {
        case Some(value) =>
          visit(value)
        case None =>
      }
    }
    def visit(tree: Tree): Unit =
      tree match {
        case MethodCall(call) if call.qual.pos.isRange =>
          var start = call.qual.pos.end
          val lastArgument = call.margss.iterator.flatten
            .filter(_.pos.isRange)
            .lastOption
          for {
            (args, i) <- call.margss.zipWithIndex
            (arg, j) <- args.zipWithIndex
          } {
            val realPos = treePos(arg)
            if (realPos.isRange) {
              val end =
                if (lastArgument.contains(arg)) tree.pos.end
                else realPos.end
              val extraEndOffset = unit.source.content(pos.point - 1) match {
                case ')' | ']' => 0
                case _ =>
                  // NOTE(olafur) Add one extra character for missing closing paren/bracket.
                  // This happens in the example "List(1, 2@@" and the compiler inferred a closing
                  // parenthesis.
                  1
              }
              val isEnclosed =
                start <= pos.start &&
                  pos.end < (end + extraEndOffset)
              if (isEnclosed) {
                activeCallsite = Some(call -> Arg(arg, i, j))
              }
              start = end
            }
            traverse(arg)
          }
          super.traverse(call.qual)
        case _ =>
          super.traverse(tree)
      }
  }

  // Same as `tree.symbol` but tries to recover from type errors
  // by using the completions API.
  def treeSymbol(tree: Tree): Symbol = {
    val symbol =
      if (tree.symbol == null) {
        tree match {
          case UnApply(qual, _) =>
            qual.symbol
          // Special case: a method call with named arguments like `foo(a = 1, b = 2)` gets desugared into the following:
          // {
          //   val x$1 = 1
          //   val x$2 = 2
          //   foo(x$1, x$2)
          // }
          case Apply(Block(_, expr), _) =>
            expr.symbol
          case _ =>
            NoSymbol
        }
      } else {
        tree.symbol
      }
    if (symbol.isDefined) {
      symbol
    } else {
      def applyQualifier(tree: Tree): Option[RefTree] =
        tree match {
          case Select(New(t: RefTree), _) => Some(t)
          case t: RefTree => Some(t)
          case TreeApply(qual, _) => applyQualifier(qual)
          case _ =>
            None
        }
      val completionFallback = for {
        qual <- applyQualifier(tree)
        completions =
          completionsAt(qual.pos.focus).results
            .filter(_.sym.javaClassSymbol.name == qual.name)
            .sorted(
              memberOrdering(
                qual.name.toString(),
                new ShortenedNames(),
                NoneCompletion
              )
            )
            .map(_.sym.javaClassSymbol)
            .distinctBy(semanticdbSymbol)
        completion <- completions match {
          case Nil =>
            None
          case head :: Nil =>
            Some(head)
          case head :: _ =>
            Some(
              head.newOverloaded(
                Option(qual.tpe).getOrElse(NoPrefix),
                completions
              )
            )
        }
        if !completion.isErroneous
      } yield completion
      completionFallback
        .orElse {
          tree match {
            case UnApply(q, _) =>
              Option(compiler.typedTreeAt(q.pos).symbol)
            case TreeApply(q @ Select(New(_), _), _) =>
              Option(compiler.typedTreeAt(q.pos).symbol)
            case Apply(tt: TypeTree, _)
                if tt.original != null && tt.original.symbol.isModule =>
              Some(tt.original.symbol.info.member(termNames.unapply))
            case _ =>
              None
          }
        }
        .getOrElse(NoSymbol)
    }
  }

  case class ParamIndex(j: Int, param: Symbol)

  def toSignatureHelp(t: EnclosingMethodCall): SignatureHelp = {
    val activeParent = t.call.nonOverload
    var activeSignature: Integer = null
    var activeParameter: Integer = null
    val shortenedNames = new ShortenedNames()
    val infos = t.alternatives.zipWithIndex.collect {
      case ((method, alternativeTpe), i) if !method.isErroneous =>

        val isActiveSignature = method == activeParent
        val tpe =
          if (isActiveSignature) t.call.qualTpe
          else alternativeTpe

        // Check if this is a case class constructor pattern by verifying that the unapply method
        // belongs to the companion object of the case class result type
        val isCaseClassConstructor =
          t.call.isUnapplyMethod && tpe.resultType.typeSymbol.isCaseClass &&
            method.owner.isModuleClass && method.owner.companionClass == tpe.resultType.typeSymbol

        val paramss: List[List[Symbol]] =
          if (!isActiveSignature) {
            if (isCaseClassConstructor) {
              val constructorParamss =
                tpe.resultType.typeSymbol.primaryConstructor.paramLists
              constructorParamss
            } else {
              mparamss(tpe, t.call.isUnapplyMethod)
            }
          } else {
            activeSignature = i
            val paramss = if (isCaseClassConstructor) {
              val constructorParamss =
                tpe.resultType.typeSymbol.primaryConstructor.paramLists
              constructorParamss
            } else {
              this.mparamss(tpe, t.call.isUnapplyMethod)
            }
            val gparamss = for {
              (params, i) <- paramss.zipWithIndex
              (param, j) <- params.zipWithIndex
            } yield (param, i, j)
            val activeIndex = gparamss.zipWithIndex.collectFirst {
              case ((param, i, j), flat) if t.activeArg.matches(param, i, j) =>
                flat
            }
            activeIndex match {
              case Some(value) =>
                val paramCount = math.max(0, gparamss.length - 1)
                activeParameter = math.min(value, paramCount)
              case _ =>
            }
            paramss
          }

        val methodType = if (!t.call.isUnapplyMethod) {
          // It is not unapply.
          tpe
        } else if (isCaseClassConstructor) {
          // It is unapply and case class.
          tpe.resultType.typeSymbol.primaryConstructor.info
        } else {
          // It is unapply and not case class.
          method.info
        }

        val finalres: SignatureInformation = toSignatureInformation(
          t,
          method,
          methodType,
          paramss,
          isActiveSignature,
          shortenedNames,
          isCaseClassConstructor
        )
        finalres
    }
    if (activeSignature == null) {
      activeSignature = 0
    }
    if (infos.isEmpty) new SignatureHelp()
    else {
      val mainSignature = infos(activeSignature)
      val deduplicated = infos
        .filter { sig =>
          sig != mainSignature && sig.getLabel() != mainSignature.getLabel()
        }
        .distinctBy(_.getLabel())

      new SignatureHelp(
        (mainSignature :: deduplicated).asJava,
        0,
        activeParameter
      )
    }
  }

  def mparamss(
      method: Type,
      isUnapplyMethod: Boolean
  ): List[List[compiler.Symbol]] = {
    method.finalResultType match {
      case TypeRef(
            _,
            cls,
            TypeRef(_, symbol, args) :: Nil
          )
          if isUnapplyMethod &&
            (cls == definitions.OptionClass || cls == definitions.SomeClass) =>
        // tuple unapply results
        if (definitions.isTupleSymbol(symbol))
          List(args.map(_.typeSymbol))
        // otherwise it's a single unapply result
        else
          List(List(symbol))
      case _ if isUnapplyMethod =>
        method.paramLists.map(_.map(_.tpe.typeSymbol))
      case _ =>
        if (method.typeParams.isEmpty) method.paramLists
        else method.typeParams :: method.paramLists

    }
  }

  def toSignatureInformation(
      t: EnclosingMethodCall,
      method: Symbol,
      methodType: Type,
      mparamss: List[List[Symbol]],
      isActiveSignature: Boolean,
      shortenedNames: ShortenedNames,
      isCaseClassConstructor: Boolean = false
  ): SignatureInformation = {
    def arg(i: Int, j: Int): Option[Tree] =
      t.call.all.lift(i).flatMap(_.lift(j))
    var k = 0
    val printerMethod = method
    val printer: SignaturePrinter = new SignaturePrinter(
      printerMethod,
      shortenedNames,
      methodType,
      includeDocs = true
    )
    val paramLabels = mparamss.zipWithIndex.flatMap { case (params, i) =>
      val byName: Map[Name, Int] =
        if (isActiveSignature) {
          (for {
            args <- t.call.all.lift(i).toList
            (AssignOrNamedArg(Ident(arg), _), argIndex) <- args.zipWithIndex
          } yield arg -> argIndex).toMap
        } else {
          Map.empty[Name, Int]
        }
      // if no by name are present then we don't want to change the order
      val firstByName = if (byName.isEmpty) Int.MaxValue else byName.values.min

      /**
       * We try to adjust the ordering when by name params are present.
       * For example:
       * ```
       *   def hello(a: Int, b: Int, c: Int, d: Int, e: Int) = ???
       *   hello(1, 2, d = 5, @@)
       * ```
       * 1 and 2 correspond to a and b, so signature help should show
       * them at the correct place at the start.
       *
       * d = 5 is a by name parameter so we want to show it next in
       * the signature.
       *
       * Since we already have by name parameter we want to show
       * `c` and `e` at the end of the signature help.
       */
      def byNamedArgumentPosition(symbol: Symbol, pos: Int): Int = {
        if (pos >= firstByName)
          // if we are past byname means we can only write by names
          byName.getOrElse(symbol.name, Int.MaxValue)
        else
          // anything that is not by name needs to go at the start
          byName.getOrElse(symbol.name, Int.MinValue)
      }
      val sortedByName = params.zipWithIndex
        .sortBy { case (sym, pos) =>
          (byNamedArgumentPosition(sym, pos), pos)
        }
        .map { case (sym, _) =>
          sym
        }
      val isByNamedOrdered = sortedByName.zip(params).exists { case (a, b) =>
        a != b
      }
      val labels = sortedByName.zipWithIndex.flatMap { case (param, j) =>
        if (param.name.startsWith(termNames.EVIDENCE_PARAM_PREFIX)) {
          Nil
        } else {
          // if byName is empty the indexes are correct, otherwise we should recalculate them
          val paramIndex = if (byName.isEmpty) Some(k) else None
          k += 1
          val label =
            /* For unapply methods we translate return value, which contains only types
             * and not parameters.
             */
            if (param.isParameter)
              printer.paramLabel(param, paramIndex)
            else
              printer.printType(param.tpe)
          val docstring = printer.paramDocstring(param, paramIndex)
          val byNameLabel =
            if (isByNamedOrdered) s"<$label>"
            else label
          val lparam = new ParameterInformation(byNameLabel)
          if (metalsConfig.isSignatureHelpDocumentationEnabled) {
            lparam.setDocumentation(docstring.toMarkupContent())
          }
          if (isActiveSignature && t.activeArg.matches(param, i, j)) {
            arg(i, j) match {
              case Some(a) if a.tpe != null && !a.tpe.isErroneous =>
                val tpe = metalsToLongString(a.tpe.widen, shortenedNames)
                if (
                  lparam.getLabel() != null &&
                  lparam.getLabel().isLeft() &&
                  !lparam.getLabel().getLeft().endsWith(tpe) &&
                  metalsConfig.isSignatureHelpDocumentationEnabled
                ) {
                  lparam.setDocumentation(
                    ("```scala\n" + tpe + "\n```\n" + docstring)
                      .toMarkupContent()
                  )
                }
              case _ =>
            }
          }
          lparam :: Nil
        }
      }
      if (labels.isEmpty && sortedByName.nonEmpty) Nil
      else labels :: Nil
    }

    val extractParamLabel: PartialFunction[ParameterInformation, String] = {
      case i if i.getLabel() != null && i.getLabel().isLeft() =>
        i.getLabel().getLeft()
    }
    val signatureLabel = if (isCaseClassConstructor) {
      paramLabels.flatten.collect(extractParamLabel).mkString("(", ", ", ")")
    } else {
      printer.methodSignature(
        paramLabels.iterator.map(_.iterator.collect(extractParamLabel)),
        printUnapply = !t.call.isUnapplyMethod || isCaseClassConstructor
      )
    }

    val signatureInformation = new SignatureInformation(signatureLabel)
    if (metalsConfig.isSignatureHelpDocumentationEnabled) {
      signatureInformation.setDocumentation(
        printer.methodDocstring.toMarkupContent()
      )
    }
    signatureInformation.setParameters(
      paramLabels.flatten.asJava
    )
    signatureInformation
  }

}
