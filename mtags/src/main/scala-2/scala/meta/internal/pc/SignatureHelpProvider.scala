package scala.meta.internal.pc

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.pc.OffsetParams

import org.eclipse.lsp4j.ParameterInformation
import org.eclipse.lsp4j.SignatureHelp
import org.eclipse.lsp4j.SignatureInformation

class SignatureHelpProvider(val compiler: MetalsGlobal) {
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
    typedTreeAt(pos)
    val enclosingApply = new EnclosingApply(pos).find(unit.body)
    val typedEnclosing = typedTreeAt(enclosingApply.pos)
    new MethodCallTraverser(unit, pos)
      .fromTree(typedEnclosing)
      .map(toSignatureHelp)
      .getOrElse(new SignatureHelp())
  }

  class EnclosingApply(pos: Position) extends Traverser {
    var last: Tree = EmptyTree
    def find(tree: Tree): Tree = {
      traverse(tree)
      last
    }
    def isValidQualifier(qual: Tree): Boolean =
      !qual.pos.includes(pos) && (qual match {
        // Ignore synthetic TupleN constructors from tuple syntax.
        case Select(ident @ Ident(TermName("scala")), TermName(tuple))
            if tuple.startsWith("Tuple") && ident.pos == qual.pos =>
          false
        case _ =>
          true
      })
    override def traverse(tree: Tree): Unit = {
      if (tree.pos.includes(pos)) {
        tree match {
          case Apply(qual, _) if isValidQualifier(qual) =>
            last = tree
          case TypeApply(qual, _) if isValidQualifier(qual) =>
            last = tree
          case AppliedTypeTree(qual, _) if isValidQualifier(qual) =>
            last = tree
          case _ =>
        }
        super.traverse(tree)
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
    val qualTpe: Type = symbol.name match {
      case termNames.unapply =>
        symbol.paramLists match {
          case (head :: Nil) :: Nil =>
            symbol.info.finalResultType match {
              case TypeRef(
                    _,
                    definitions.OptionClass,
                    tpe @ TypeRef(_, tuple, args) :: Nil
                  ) =>
                val ctor = head.tpe.typeSymbol.primaryConstructor
                val params = ctor.paramLists.headOption.getOrElse(Nil)
                val toZip = args match {
                  case Nil => tpe
                  case _ => args
                }
                val isAlignedTypes = toZip.lengthCompare(params.length) == 0 &&
                  toZip.zip(params).forall {
                    case (a, b) =>
                      a == b.tpe ||
                        b.tpe.typeSymbol.isTypeParameter
                  }
                if (isAlignedTypes) {
                  ctor.info
                } else {
                  symbol.info
                }
              case _ =>
                symbol.info
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
    def alternatives: List[Symbol] =
      symbol match {
        case o: ModuleSymbol =>
          o.info.member(compiler.nme.apply).alternatives
        case o: ClassSymbol =>
          o.info.member(compiler.termNames.CONSTRUCTOR).alternatives
        case m: MethodSymbol =>
          m.owner.info.member(symbol.name).alternatives
        case _ =>
          symbol.alternatives
      }
    def nonOverload: Symbol =
      if (!symbol.isOverloaded) symbol
      else alternatives.headOption.getOrElse(symbol)
    def gparamss: List[List[Symbol]] = {
      if (qualTpe.typeParams.isEmpty) nonOverload.paramLists
      else nonOverload.typeParams :: nonOverload.paramLists
    }
    def all: List[List[Tree]] =
      if (qualTpe.typeParams.isEmpty) argss
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
    def alternatives: List[Symbol] = call.alternatives
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
                else arg.pos.end
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
            case UnApply(q, a) =>
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
      case (method, i) if !method.isErroneous =>
        val isActiveSignature = method == activeParent
        val tpe =
          if (isActiveSignature) t.call.qualTpe
          else method.info
        val paramss: List[List[Symbol]] =
          if (!isActiveSignature) {
            mparamss(tpe)
          } else {
            activeSignature = i
            val paramss = this.mparamss(tpe)
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
        toSignatureInformation(
          t,
          method,
          tpe,
          paramss,
          isActiveSignature,
          shortenedNames
        )
    }
    new SignatureHelp(infos.asJava, activeSignature, activeParameter)
  }

  def mparamss(method: Type): List[List[compiler.Symbol]] = {
    if (method.typeParams.isEmpty) method.paramLists
    else method.typeParams :: method.paramLists
  }

  def toSignatureInformation(
      t: EnclosingMethodCall,
      method: Symbol,
      methodType: Type,
      mparamss: List[List[Symbol]],
      isActiveSignature: Boolean,
      shortenedNames: ShortenedNames
  ): SignatureInformation = {
    def arg(i: Int, j: Int): Option[Tree] =
      t.call.all.lift(i).flatMap(_.lift(j))
    var k = 0
    val printer = new SignaturePrinter(
      method,
      shortenedNames,
      methodType,
      includeDocs = true
    )
    val paramLabels = mparamss.zipWithIndex.flatMap {
      case (params, i) =>
        val byName: Map[Name, Int] =
          if (isActiveSignature) {
            (for {
              args <- t.call.all.lift(i).toList
              (AssignOrNamedArg(Ident(arg), _), argIndex) <- args.zipWithIndex
            } yield arg -> argIndex).toMap
          } else {
            Map.empty[Name, Int]
          }
        def byNamedArgumentPosition(symbol: Symbol): Int = {
          byName.getOrElse(symbol.name, Int.MaxValue)
        }
        val sortedByName = params.zipWithIndex
          .sortBy {
            case (sym, pos) =>
              (byNamedArgumentPosition(sym), pos)
          }
          .map {
            case (sym, _) => sym
          }
        val isByNamedOrdered = sortedByName.zip(params).exists {
          case (a, b) => a != b
        }
        val labels = sortedByName.zipWithIndex.flatMap {
          case (param, j) =>
            if (param.name.startsWith(termNames.EVIDENCE_PARAM_PREFIX)) {
              Nil
            } else {
              val index = k
              k += 1
              val label = printer.paramLabel(param, index)
              val docstring = printer.paramDocstring(index)
              val byNameLabel =
                if (isByNamedOrdered) s"<$label>"
                else label
              val lparam = new ParameterInformation(byNameLabel)
              if (metalsConfig.isSignatureHelpDocumentationEnabled) {
                lparam.setDocumentation(docstring.toMarkupContent)
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
                        ("```scala\n" + tpe + "\n```\n" + docstring).toMarkupContent
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
    val signatureInformation = new SignatureInformation(
      printer.methodSignature(
        paramLabels.iterator.map(_.iterator.collect {
          case i if i.getLabel() != null && i.getLabel().isLeft() =>
            i.getLabel().getLeft()
        })
      )
    )
    if (metalsConfig.isSignatureHelpDocumentationEnabled) {
      signatureInformation.setDocumentation(
        printer.methodDocstring.toMarkupContent
      )
    }
    signatureInformation.setParameters(
      paramLabels.iterator.flatten.toSeq.asJava
    )
    signatureInformation
  }

}
