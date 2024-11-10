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
              // in this case we could have list, tuple, classes, etc and we would need to assess each case to extract the right argument type
              val typ = arg.tpe
              if (typ != null)
                Some(prettyType(arg.tpe))
              else {
                logger.warning(
                  "infer-method: could not infer type of argument, defaulting to Any"
                )
                Some("Any")
              }
          }
          tp.map(tp => s"arg$index: $tp")

        }
      if (paramsTypes.forall(_.nonEmpty)) {
        Some(paramsTypes.flatten.mkString(", "))
      } else None

    }

    type FileURI = String

    def getPositionUri(position: Position): String = scala.util
      .Try(position.source.toString()) // in tests this is undefined
      .toOption
      .getOrElse(params.uri().toString())

    def signature(
        name: String,
        paramsString: Option[String],
        retType: Option[String],
        postProcess: String => String,
        position: Option[Position]
    ): List[(FileURI, TextEdit)] = {

      val lastApplyPos = position.getOrElse(insertPosition())
      val fileUri = getPositionUri(lastApplyPos)
      val indentString =
        indentation(params.text(), lastApplyPos.start - 1)
      val retTypeString = retType match {
        case None =>
          ""
        case Some(retTypeStr) =>
          s": $retTypeStr"
      }
      val parameters = paramsString.map(s => s"($s)").getOrElse("")
      val full =
        s"def ${name}$parameters$retTypeString = ???\n$indentString"
      val methodInsertPosition = lastApplyPos.toLsp
      methodInsertPosition.setEnd(methodInsertPosition.getStart())
      val newEdits = new TextEdit(
        methodInsertPosition,
        postProcess(full)
      ) :: additionalImports
      newEdits.map(textEdit => (fileUri, textEdit))
    }

    def unimplemented(c: String): List[(FileURI, TextEdit)] = {
      logger.warning(
        s"This case ($c) is not currently supported by the infer-method code action."
      )
      throw new RuntimeException()
      Nil
    }
    def makeEditsForApplyWithUnknownName(
        arguments: List[Tree],
        containingMethod: Name,
        errorMethod: Ident,
        nonExistent: Apply
    ): List[(FileURI, TextEdit)] = {
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
                paramsString = Option(paramsString),
                retType = Some(ret),
                postProcess = identity,
                position = None
              )
            case _ =>
              unimplemented("apply-with-unknown-name-inner")
          }

        case _ =>
          unimplemented("apply-with-unknown-name-outer")
      }
    }
    def makeEditsForListApply(
        arguments: List[Tree],
        containingMethod: Name,
        errorMethod: Ident
    ): List[(FileURI, TextEdit)] = {
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
              // def method1(s : (String, Float) => Int) = 123
              // method1(<<nonExistent>>)
              case TypeRef(_, _, args) if definitions.isFunctionType(tpe) =>
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
                  Option(paramsString),
                  Option(ret),
                  identity,
                  None
                )

              case _ =>
                //    def method1(s : => Int) = 123
                //    method1(<<nonExistent>>)
                val ret = tpe.toString().replace("=>", "").trim()
                signature(
                  name = errorMethod.name.toString(),
                  None,
                  Option(ret),
                  identity,
                  Option(lastApplyPos)
                )
            }

          case _ =>
            unimplemented("list-apply")
        }
      } else {
        signature(
          name = errorMethod.name.toString(),
          paramsString = Option(argumentsString(arguments).getOrElse("")),
          retType = None,
          postProcess = identity,
          position = None
        )
      }
    }
    def makeEditsForListApplyWithoutArgs(
        argumentList: Name,
        errorMethod: Ident
    ): List[(FileURI, TextEdit)] = {
      val listSymbol = context.lookupSymbol(argumentList, _ => true)
      if (listSymbol.isSuccess) {
        listSymbol.symbol.tpe match {
          // need to find the type of the value on which we are mapping
          case TypeRef(_, _, TypeRef(_, inputType, _) :: Nil) =>
            val paramsString = s"arg0: ${prettyType(inputType.tpe)}"
            signature(
              name = errorMethod.name.toString(),
              Option(paramsString),
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
              Option(paramsString),
              None,
              identity,
              None
            )
          case _ =>
            unimplemented("list-apply-without-args-inner")
        }
      } else unimplemented("list-apply-without-args-outer")
    }
    def makeEditsMethodObject(
        arguments: Option[List[Tree]],
        container: Name,
        errorMethod: Select
    ): List[(FileURI, TextEdit)] = {
      def makeClassMethodEdits(
          template: Template
      ): List[(FileURI, TextEdit)] = {
        val insertPos: Position =
          inferEditPosition(template)
        val templateFileUri = template.pos.source.content
          .map(_.toString)
          .mkString
        if (params.uri().toString() != getPositionUri(insertPos)) {
          unimplemented("method in external file")
        } else
          signature(
            name = errorMethod.name.toString(),
            paramsString = arguments
              .fold(Option.empty[String])(argumentsString(_) orElse Option("")),
            retType = None,
            postProcess = method => {
              if (
                hasBody(
                  templateFileUri,
                  template
                ).isDefined
              )
                s"\n  $method"
              else s" {\n  $method}"
            },
            position = Option(insertPos)
          )
      }

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
            val containerClass =
              context.lookupSymbol(classSymbol.name, _ => true)
            // this gives me the position of the class
            typedTreeAt(containerClass.symbol.pos) match {
              // class case
              case ClassDef(
                    _,
                    _,
                    _,
                    template
                  ) =>
                makeClassMethodEdits(template)
              // trait case
              case ModuleDef(
                    _,
                    _,
                    template
                  ) =>
                makeClassMethodEdits(template)
              case _ =>
                // object Y {}
                // Y.nonExistent(1,2,3)
                typedTreeAt(containerSymbol.symbol.pos) match {
                  // class case
                  case ClassDef(
                        _,
                        _,
                        _,
                        template
                      ) =>
                    makeClassMethodEdits(template)
                  // trait case
                  case ModuleDef(
                        _,
                        _,
                        template
                      ) =>
                    makeClassMethodEdits(template)
                  case _ =>
                    unimplemented("object-method-inner")
                }
            }
          case _ =>
            unimplemented("object-method-outer")
        }
      } else
        unimplemented("object-method")
    }
    val uriTextEditPairs = typedTree match {
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
            makeEditsForApplyWithUnknownName(
              arguments,
              containingMethod,
              errorMethod,
              nonExistent
            )
          // <<nonExistent>>(param1, param2)
          // val a: Int = <<nonExistent>>(param1, param2)
          case (_: Ident) :: Apply(
                containing @ Ident(
                  nonExistent
                ),
                arguments
              ) :: _ if containing.isErrorTyped =>
            signature(
              name = nonExistent.toString(),
              paramsString = Option(argumentsString(arguments).getOrElse("")),
              retType = None,
              postProcess = identity,
              position = None
            )
          // List(1, 2, 3).map(<<nonExistent>>)
          // List((1, 2)).map{case (x,y) => <<nonExistent>>(x,y)}
          case (_: Ident) :: Apply(
                Ident(containingMethod),
                arguments
              ) :: _ =>
            makeEditsForListApply(arguments, containingMethod, errorMethod)
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
              paramsString =
                Option(argumentsString(arguments.take(1)).getOrElse("")),
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
            makeEditsForListApplyWithoutArgs(argumentList, errorMethod)
          case _ => unimplemented("simple-case")
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
            makeEditsMethodObject(Option(arguments), container, errorMethod)
          // X.<<nonExistent>>
          case Select(Ident(container), _) :: _ =>
            makeEditsMethodObject(Option.empty, container, errorMethod)
          // X.<<nonExistent>>
          case Select(Select(_, container), _) :: _ =>
            makeEditsMethodObject(Option.empty, container, errorMethod)
          case _ =>
            unimplemented("other-than-method")
        }
      case _ =>
        unimplemented("other-than-ident-or-select")
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

  // taken from OverrideCompletion
  /**
   * Get the position to insert implements for the given Template.
   * `class Foo extends Bar {}` => retuning position would be right after the opening brace.
   * `class Foo extends Bar` => retuning position would be right after `Bar`.
   *
   * @param t the enclosing template for the class/object/trait we are implementing.
   */
  private def inferEditPosition(t: Template): Position = {
    // to get text via reflection because Template could be in a different file
    // we decided to not support other files, but we may want to go back to it
    //
    // val text = t.pos.source.content.map(_.toString).mkString
    val text = params.text()
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
