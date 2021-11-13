package scala.meta.internal.pc

import scala.meta.pc.OffsetParams

import org.eclipse.lsp4j.TextEdit
import scala.annotation.tailrec

/**
 * Tries to calculate edits needed to create a method that will fix missing symbol
 * in all the places that it is possible such as:
 * - apply inside method invocation `method(nonExistent(param))`
 * - method in val definition `val value: DefinedType = nonExistent(param)` TODO
 * - lambda expression `list.map(nonExistent)`
 *
 * Limitations:
 *   - cannot work with an expression inside the parameter, since it's not typechecked
 * @param compiler Metals presentation compiler
 * @param params position and actual source
 */
final class InferredMethodProvider(
    val compiler: MetalsGlobal,
    params: OffsetParams
) {
  import compiler._

  def inferredMethodEdits(): List[TextEdit] = {

    val unit = addCompilationUnit(
      code = params.text(),
      filename = params.uri().toString(),
      cursor = None
    )

    val pos = unit.position(params.offset)
    val typedTree = typedTreeAt(pos)
    val importPosition = autoImportPosition(pos, params.text())
    val context = doLocateImportContext(pos)
    val re: scala.collection.Map[Symbol, Name] = renamedSymbols(context)
    val history = new ShortenedNames(
      lookupSymbol = name =>
        context.lookupSymbol(name, sym => !sym.isStale) :: Nil,
      config = renameConfig,
      renames = re
    )

    def additionalImports = importPosition match {
      case None =>
        // No import position means we can't insert an import without clashing with
        // existing symbols in scope, so we just do nothing
        Nil
      case Some(importPosition) =>
        history.autoImports(pos, importPosition)
    }

    def prettyType(tpe: Type) =
      metalsToLongString(tpe.widen.finalResultType, history)

    def argumentsString(args: List[Tree]): Option[String] = {
      val paramsTypes = args.zipWithIndex
        .map { case (arg, index) =>
          val tp = arg match {
            case Ident(name) =>
              val found = context.lookupSymbol(name, _ => true)
              if (found.isSuccess)
                Some(prettyType(found.symbol.tpe))
              else None
            case _ =>
              val typ = arg.tpe
              if (typ != null)
                Some(prettyType(arg.tpe))
              else
                None
          }
          tp.map(tp => s"arg$index: $tp")

        }
      if (paramsTypes.forall(_.nonEmpty)) {
        Some(paramsTypes.flatten.mkString(", "))
      } else None

    }

    def signature(
        name: String,
        paramsString: String,
        retType: Option[String]
    ): List[TextEdit] = {

      val lastApplyPos = insertPosition()
      val indentString =
        indentation(params.text(), lastApplyPos.start - 1)
      val retTypeString = retType match {
        case None =>
          ""
        case Some(retTypeStr) =>
          s": $retTypeStr"
      }
      val full =
        s"def ${name}($paramsString)$retTypeString = ???\n$indentString"
      val methodInsertPosition = lastApplyPos.toLSP
      methodInsertPosition.setEnd(methodInsertPosition.getStart())
      new TextEdit(
        methodInsertPosition,
        full
      ) :: additionalImports
    }

    typedTree match {
      case errorMethod: Ident if errorMethod.isErroneous =>
        lastVisitedParentTrees match {
          /**
           * Works for apply with unknown name:
           * ```scala
           * method(nonExistent(param))
           * ```
           */
          case _ :: (nonExistent: Apply) :: Apply(
                Ident(containingMethod),
                arguments
              ) :: _ =>
            val argumentString = argumentsString(nonExistent.args)

            val methodSymbol = context.lookupSymbol(containingMethod, _ => true)
            argumentString match {
              case Some(paramsString) =>
                val retIndex = arguments.indexWhere(_.pos.includes(pos))
                methodSymbol.symbol.tpe match {
                  case MethodType(methodParams, _) if retIndex >= 0 =>
                    val ret = prettyType(methodParams(retIndex).tpe)
                    signature(
                      errorMethod.name.toString(),
                      paramsString,
                      Some(ret)
                    )
                  case _ => Nil
                }

              case _ => Nil
            }

          // nonExistent(param1, param2)
          // val a: Int = nonExistent(param1, param2) TODO
          case (_: Ident) :: Apply(
                containing @ Ident(
                  nonExistent
                ),
                arguments
              ) :: _ if containing.isErrorTyped =>
            val argumentString = argumentsString(arguments)
            argumentString match {
              case Some(paramsString) =>
                signature(nonExistent.toString(), paramsString, None)
              case None => Nil
            }

          // List(1, 2, 3).map(nonExistent)
          case (_: Ident) :: Apply(
                Ident(containingMethod), // TODO doesn't work with Select
                arguments
              ) :: _ =>
            val methodSymbol = context.lookupSymbol(containingMethod, _ => true)

            if (methodSymbol.isSuccess) {
              val retIndex = arguments.indexWhere(_.pos.includes(pos))
              methodSymbol.symbol.tpe match {
                case MethodType(methodParams, _) if retIndex >= 0 =>
                  val lastApplyPos = insertPosition()
                  val indentString =
                    indentation(params.text(), lastApplyPos.start - 1)

                  val tpe = methodParams(retIndex).tpe
                  tpe match {
                    case TypeRef(_, _, args)
                        if definitions.isFunctionType(tpe) =>
                      val params = args.take(args.size - 1)
                      val resultType = args.last
                      val paramsString =
                        params.zipWithIndex
                          .map { case (p, index) =>
                            s"arg$index: ${prettyType(p)}"
                          }
                          .mkString(", ")
                      val ret = prettyType(resultType)
                      val full =
                        s"def ${errorMethod.name}($paramsString): $ret = ???\n$indentString"
                      val methodInsertPosition = lastApplyPos.toLSP
                      methodInsertPosition.setEnd(
                        methodInsertPosition.getStart()
                      )

                      new TextEdit(
                        methodInsertPosition,
                        full
                      ) :: additionalImports

                    case _ =>
                      Nil
                  }

                case _ => Nil
              }
            } else {
              Nil
            }
          case other =>
            // pprint.log(other)
            Nil
        }
      case _ =>
        Nil
    }
  }

  private def insertPosition(): Position = {
    val blockOrTemplateIndex =
      lastVisitedParentTrees.tail.indexWhere {
        case _: Block | _: Template => true
        case _ => false
      }
    lastVisitedParentTrees(blockOrTemplateIndex).pos
  }

  private def indentation(text: String, pos: Int): String =
    if (pos > 0) {
      val isSpace = text(pos) == ' '
      val isTab = text(pos) == '\t'
      val indent =
        countIndent(params.text(), pos, 0)

      if (isSpace) " " * indent else if (isTab) "\t" * indent else ""
    } else ""

  @tailrec
  private def countIndent(text: String, index: Int, acc: Int): Int = {
    if (text(index) != '\n') countIndent(text, index - 1, acc + 1)
    else acc
  }
}
