package scala.meta.internal.pc

import scala.annotation.tailrec
import scala.collection.JavaConverters._

import scala.meta.pc.OffsetParams

import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.WorkspaceEdit

/**
 * Tries to calculate edits needed to create a method that will fix missing symbol
 * in all the places that it is possible such as:
 * - apply inside method invocation `method(nonExistent(param))`
 * - method in val definition `val value: DefinedType = nonExistent(param)` TODO
 * - lambda expression `list.map(nonExistent)`
 * - class method `someClass.nonExistent(param)`
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

  def inferredMethodEdits(): WorkspaceEdit = {

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

    type FileURI = String

    def signature(
        name: String,
        paramsString: String,
        retType: Option[String],
        postProcess: String => String,
        position: Option[Position]
    ): List[(FileURI, TextEdit)] = {

      val lastApplyPos = position.getOrElse(insertPosition())
      val fileUri = scala.util
        .Try(lastApplyPos.source.toString()) // in tests this is undefined
        .toOption
        .getOrElse(params.uri().toString())
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
      val methodInsertPosition = lastApplyPos.toLsp
      methodInsertPosition.setEnd(methodInsertPosition.getStart())
      val newEdits = new TextEdit(
        methodInsertPosition,
        postProcess(full)
      ) :: additionalImports
      // TODO not sure if additionalImports needs to happen in the current document or the edited one
      newEdits.map(textEdit => (fileUri, textEdit))
    }

    val uriTextEditPairs = typedTree match {
      // case errorMethod if errorMethod.toString().contains("lol") =>
      //   pprint.log(lastVisitedParentTrees)
      //   Nil
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
              ) :: _ if nonExistent.isErrorTyped =>
            val argumentString = argumentsString(nonExistent.args)

            val methodSymbol = context.lookupSymbol(containingMethod, _ => true)
            argumentString match {
              case Some(paramsString) =>
                val retIndex = arguments.indexWhere(_.pos.includes(pos))
                methodSymbol.symbol.tpe match {
                  case MethodType(methodParams, _) if retIndex >= 0 =>
                    val ret = prettyType(methodParams(retIndex).tpe)
                    signature(
                      name = errorMethod.name.toString(),
                      paramsString = paramsString,
                      retType = Some(ret),
                      postProcess = identity,
                      position = None
                    )
                  case _ =>
                    Nil
                }

              case _ =>
                Nil
            }
          // nonExistent(param1, param2)
          // val a: Int = nonExistent(param1, param2) TODO
          case (_: Ident) :: Apply(
                containing @ Ident(
                  nonExistent
                ),
                arguments
              ) :: _ if containing.isErrorTyped =>
            signature(
              name = nonExistent.toString(),
              paramsString = argumentsString(arguments).getOrElse(""),
              retType = None,
              postProcess = identity,
              position = None
            )
          // List(1, 2, 3).map(nonExistent)
          // List((1, 2)).map{case (x,y) => otherMethod(x,y)}
          case (_: Ident) :: Apply(
                Ident(containingMethod),
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
                      val paramsString =
                        params.zipWithIndex
                          .map { case (p, index) =>
                            s"arg$index: ${prettyType(p)}"
                          }
                          .mkString(", ")
                      val resultType = args.last
                      val ret = prettyType(resultType)
                      signature(
                        name = errorMethod.name.toString(),
                        paramsString,
                        Option(ret),
                        identity,
                        None
                      )

                    case _ =>
                      //    def method1(s : => Int) = 123
                      //    method1(<<otherMethod>>)
                      val ret = tpe.toString().replace("=>", "").trim()
                      val full =
                        s"def ${errorMethod.name}: $ret = ???\n$indentString"
                      signature(
                        name = errorMethod.name.toString(),
                        "",
                        None,
                        _ => full,
                        Option(lastApplyPos)
                      )
                  }

                case _ =>
                  Nil
              }
            } else {
              signature(
                name = errorMethod.name.toString(),
                paramsString = argumentsString(arguments).getOrElse(""),
                retType = None,
                postProcess = identity,
                position = None
              )
            }
          // List(1,2,3).map(myFn)
          case (_: Ident) :: Apply(
                Select(
                  Apply(
                    Ident(_),
                    arguments
                  ),
                  _
                ),
                _
              ) :: _ =>
            signature(
              name = errorMethod.name.toString(),
              paramsString = argumentsString(arguments).getOrElse(""),
              retType = None,
              postProcess = identity,
              position = None
            )
          // val list = List(1,2,3)
          // list.map(nonExistent)
          case (Ident(_)) :: Apply(
                Select(Ident(argumentList), _),
                _ :: Nil
              ) :: _ =>
            // need to find the type of the value on which we are mapping
            val listSymbol = context.lookupSymbol(argumentList, _ => true)
            if (listSymbol.isSuccess) {
              listSymbol.symbol.tpe match {
                case TypeRef(_, _, TypeRef(_, inputType, _) :: Nil) =>
                  val paramsString = s"arg0: ${prettyType(inputType.tpe)}"
                  signature(
                    name = errorMethod.name.toString(),
                    paramsString,
                    None,
                    identity,
                    None
                  )
                case NullaryMethodType(
                      TypeRef(_, _, TypeRef(_, inputType, _) :: Nil)
                    ) =>
                  val paramsString = s"arg0: ${prettyType(inputType.tpe)}"
                  signature(
                    name = errorMethod.name.toString(),
                    paramsString,
                    None,
                    identity,
                    None
                  )
                case _ =>
                  Nil
              }
            } else Nil
          case _ => Nil
        }
      case errorMethod: Select =>
        lastVisitedParentTrees match {
          // class X{}
          // val x: X = ???
          // x.nonExistent(1,true,"string")
          case Select(Ident(container), _) :: Apply(
                _,
                arguments
              ) :: _ =>
            // pprint.log(lastVisitedParentTrees)
            // we need to get the type of the container of our undefined method
            val containerSymbol = context.lookupSymbol(container, _ => true)
            if (containerSymbol.isSuccess) {
              (if (containerSymbol.symbol.tpe.isInstanceOf[NullaryMethodType])
                 containerSymbol.symbol.tpe
                   .asInstanceOf[NullaryMethodType]
                   .resultType
               else containerSymbol.symbol.tpe) match {
                case TypeRef(_, classSymbol, _) =>
                  // we get the position of the container
                  // class because we want to add the method
                  // definition there
                  //

                  val containerClass =
                    context.lookupSymbol(classSymbol.name, _ => true)
                  // this gives me the position of the class, but what about its body
                  typedTreeAt(containerClass.symbol.pos) match {
                    case ClassDef(
                          _,
                          _,
                          _,
                          template
                        ) =>
                      val insertPos: Position =
                        inferEditPosition(template)

                      // val ret = prettyType(methodParams(retIndex).tpe)
                      signature(
                        name = errorMethod.name.toString(),
                        paramsString = argumentsString(arguments).getOrElse(""),
                        retType = None,
                        postProcess = method => {
                          if (
                            hasBody(
                              template.pos.source.content
                                .map(_.toString)
                                .mkString,
                              template
                            ).isDefined
                          )
                            s"\n  $method"
                          else s" {\n  $method}"
                        },
                        position = Option(insertPos)
                      )
                    case ModuleDef(
                          _,
                          _,
                          template
                        ) =>
                      val insertPos: Position =
                        inferEditPosition(template)

                      // val ret = prettyType(methodParams(retIndex).tpe)
                      signature(
                        name = errorMethod.name.toString(),
                        paramsString = argumentsString(arguments).getOrElse(""),
                        retType = None,
                        postProcess = method => {
                          if (
                            hasBody(
                              template.pos.source.content
                                .map(_.toString)
                                .mkString,
                              template
                            ).isDefined
                          )
                            s"\n  $method"
                          else s" {\n  $method}"
                        },
                        position = Option(insertPos)
                      )
                    case _ =>
                      // object Y {}
                      // Y.nonExistent(1,2,3)
                      typedTreeAt(containerSymbol.symbol.pos) match {
                        case ClassDef(
                              _,
                              _,
                              _,
                              template
                            ) =>
                          val insertPos: Position =
                            inferEditPosition(template)

                          // val ret = prettyType(methodParams(retIndex).tpe)
                          signature(
                            name = errorMethod.name.toString(),
                            paramsString =
                              argumentsString(arguments).getOrElse(""),
                            retType = None,
                            postProcess = method => {
                              if (
                                hasBody(
                                  template.pos.source.content
                                    .map(_.toString)
                                    .mkString,
                                  template
                                ).isDefined
                              )
                                s"\n  $method"
                              else s" {\n  $method}"
                            },
                            position = Option(insertPos)
                          )
                        case ModuleDef(
                              _,
                              _,
                              template
                            ) =>
                          val insertPos: Position =
                            inferEditPosition(template)
                          signature(
                            name = errorMethod.name.toString(),
                            paramsString =
                              argumentsString(arguments).getOrElse(""),
                            retType = None,
                            postProcess = method => {
                              if (
                                hasBody(
                                  template.pos.source.content
                                    .map(_.toString)
                                    .mkString,
                                  template
                                ).isDefined
                              )
                                s"\n  $method"
                              else s" {\n  $method}"
                            },
                            position = Option(insertPos)
                          )
                        case _ =>
                          Nil
                      }
                  }
                case _ =>
                  Nil
              }
            } else
              Nil
          case _ => Nil
        }
      case _ =>
        Nil
    }
    new WorkspaceEdit(
      uriTextEditPairs
        .groupBy(_._1)
        .map { case (k, kvs) => (k, kvs.map(_._2).asJava) }
        .toMap
        .asJava
    )
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

  // TODO taken from OverrideCompletion: move into a utility?
  /**
   * Get the position to insert implements for the given Template.
   * `class Foo extends Bar {}` => retuning position would be right after the opening brace.
   * `class Foo extends Bar` => retuning position would be right after `Bar`.
   *
   * @param text the text of the original source code.
   * @param t the enclosing template for the class/object/trait we are implementing.
   */
  private def inferEditPosition(t: Template): Position = {
    // get text via reflection because Template could be in a different file
    val text = t.pos.source.content.map(_.toString).mkString
    hasBody(text, t)
      .map { offset => t.pos.withStart(offset + 1).withEnd(offset + 1) }
      .getOrElse(
        t.pos.withStart(t.pos.end)
      )
  }

  /**
   * Check if the given Template has body or not:
   * `class Foo extends Bar {}` => Some(position of `{`)
   * `class Foo extends Bar` => None
   *
   * @param text the text of the original source code.
   * @param t the enclosing template for the class/object/trait we are implementing.
   * @return if the given Template has body, returns the pos of opening brace, otherwise returns None
   */
  private def hasBody(text: String, t: Template): Option[Int] = {
    val start = t.pos.start
    val offset =
      if (t.self.tpt.isEmpty)
        text.indexOf('{', start)
      else text.indexOf("=>", start) + 1
    if (offset > 0 && offset < t.pos.end) Some(offset)
    else None
  }
}
