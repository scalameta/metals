package scala.meta.internal.pc

import scala.annotation.tailrec

import scala.meta.internal.metals.PcQueryContext
import scala.meta.pc.OffsetParams

import org.eclipse.lsp4j.TextEdit

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
)(implicit queryInfo: PcQueryContext) {
  import compiler._
  val unit: RichCompilationUnit = addCompilationUnit(
    code = params.text(),
    filename = params.uri().toString(),
    cursor = None
  )

  val pos: Position = unit.position(params.offset)
  val typedTree: Tree = typedTreeAt(pos)
  val importPosition: Option[AutoImportPosition] =
    autoImportPosition(pos, params.text())
  val context: Context = doLocateImportContext(pos)
  val re: scala.collection.Map[Symbol, Name] = renamedSymbols(context)
  val history = new ShortenedNames(
    lookupSymbol = name =>
      context.lookupSymbol(name, sym => !sym.isStale) :: Nil,
    config = renameConfig,
    renames = re
  )

  def inferredMethodEdits(): Either[String, List[TextEdit]] = {

    val textEdits = typedTree match {
      case errorMethod: Ident if errorMethod.isErroneous =>
        val errorMethodName = Identifier.backtickWrap(errorMethod.name.decoded)
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
              errorMethodName,
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
            makeEditsForListApply(arguments, containingMethod, errorMethodName)
          // List(1,2,3).map(myFn)
          case (_: Ident) :: Apply(
                Select(
                  Apply(
                    Ident(_),
                    _
                  ),
                  _
                ),
                _
              ) :: _ =>
            unimplemented(errorMethodName)
          // val list = List(1,2,3)
          // list.map(nonExistent)
          case (Ident(_)) :: Apply(
                Select(Ident(argumentList), _),
                _ :: Nil
              ) :: _ =>
            makeEditsForListApplyWithoutArgs(argumentList, errorMethodName)
          case _ => unimplemented(errorMethodName)
        }
      case errorMethod: Select =>
        val errorMethodName = Identifier.backtickWrap(errorMethod.name.decoded)
        lastVisitedParentTrees match {
          // class X{}
          // val x: X = ???
          // x.nonExistent(1,true,"string")
          case Select(Ident(container), _) :: Apply(
                _,
                arguments
              ) :: _ =>
            makeEditsMethodObject(Option(arguments), container, errorMethodName)
          // X.<<nonExistent>>
          case Select(Ident(container), _) :: _ =>
            makeEditsMethodObject(Option.empty, container, errorMethodName)
          // X.<<nonExistent>>
          case Select(Select(_, container), _) :: _ =>
            makeEditsMethodObject(Option.empty, container, errorMethodName)
          case _ =>
            unimplemented(errorMethodName)
        }
      case tree =>
        unimplemented(tree.summaryString)
    }
    textEdits
  }

  private def additionalImports = importPosition match {
    case None =>
      // No import position means we can't insert an import without clashing with
      // existing symbols in scope, so we just do nothing
      Nil
    case Some(importPosition) =>
      history.autoImports(pos, importPosition)
  }

  private def prettyType(tpe: Type) =
    metalsToLongString(tpe.widen.finalResultType, history)

  private def argumentsString(args: List[Tree]): Option[String] = {
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
              logger.warn(
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

  private def getPositionUri(position: Position): String = scala.util
    .Try(position.source.toString()) // in tests this is undefined
    .toOption
    .getOrElse(params.uri().toString())

  private def unimplemented(
      name: String
  ): Either[String, List[TextEdit]] = {
    Left(
      s"Could not infer method for `$name`, please report an issue in github.com/scalameta/metals"
    )
  }

  private def signature(
      name: String,
      paramsString: Option[String],
      retType: Option[String],
      postProcess: String => String,
      position: Option[Position]
  ): Either[String, List[TextEdit]] = {

    val lastApplyPos = position.getOrElse(insertPosition())
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
    Right(newEdits)
  }

  private def makeEditsForApplyWithUnknownName(
      arguments: List[Tree],
      containingMethod: Name,
      errorMethodName: String,
      nonExistent: Apply
  ): Either[String, List[TextEdit]] = {
    val argumentString = argumentsString(nonExistent.args)
    val methodSymbol = context.lookupSymbol(containingMethod, _ => true)
    argumentString match {
      case Some(paramsString) =>
        val retIndex = arguments.indexWhere(_.pos.includes(pos))
        methodSymbol.symbol.tpe match {
          case MethodType(methodParams, _) if retIndex >= 0 =>
            val ret = prettyType(methodParams(retIndex).tpe)
            signature(
              name = errorMethodName,
              paramsString = Option(paramsString),
              retType = Some(ret),
              postProcess = identity,
              position = None
            )
          case _ =>
            unimplemented(errorMethodName)
        }

      case _ =>
        unimplemented(errorMethodName)
    }
  }

  private def makeEditsForListApply(
      arguments: List[Tree],
      containingMethod: Name,
      errorMethodName: String
  ): Either[String, List[TextEdit]] = {
    val methodSymbol = context.lookupSymbol(containingMethod, _ => true)
    if (methodSymbol.isSuccess) {
      val retIndex = arguments.indexWhere(_.pos.includes(pos))
      methodSymbol.symbol.tpe match {
        case MethodType(methodParams, _) if retIndex >= 0 =>
          val lastApplyPos = insertPosition()
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
                name = errorMethodName,
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
                name = errorMethodName,
                None,
                Option(ret),
                identity,
                Option(lastApplyPos)
              )
          }

        case _ =>
          unimplemented(errorMethodName)
      }
    } else {
      signature(
        name = errorMethodName,
        paramsString = Option(argumentsString(arguments).getOrElse("")),
        retType = None,
        postProcess = identity,
        position = None
      )
    }
  }

  private def makeEditsForListApplyWithoutArgs(
      argumentList: Name,
      errorMethodName: String
  ): Either[String, List[TextEdit]] = {
    val listSymbol = context.lookupSymbol(argumentList, _ => true)
    if (listSymbol.isSuccess) {
      listSymbol.symbol.tpe match {
        // need to find the type of the value on which we are mapping
        case TypeRef(_, _, TypeRef(_, inputType, _) :: Nil) =>
          val paramsString = s"arg0: ${prettyType(inputType.tpe)}"
          signature(
            name = errorMethodName,
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
            name = errorMethodName,
            Option(paramsString),
            None,
            identity,
            None
          )
        case _ =>
          unimplemented(errorMethodName)
      }
    } else
      unimplemented(errorMethodName)
  }

  private def makeEditsMethodObject(
      arguments: Option[List[Tree]],
      container: Name,
      errorMethodName: String
  ): Either[String, List[TextEdit]] = {
    def makeClassMethodEdits(
        template: Template
    ): Either[String, List[TextEdit]] = {
      val insertPos: Position =
        inferEditPosition(template)
      val templateFileUri = template.pos.source.content
        .map(_.toString)
        .mkString
      if (params.uri().toString() != getPositionUri(insertPos)) {
        Left(
          "Inferring method only works in the current file, cannot add method to types outside of it."
        )
      } else
        signature(
          name = errorMethodName,
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
    val containerName = context.lookupSymbol(container, _ => true)

    if (containerName.isSuccess) {
      val containerSymbolType =
        if (containerName.symbol.tpe.isInstanceOf[NullaryMethodType])
          containerName.symbol.tpe
            .asInstanceOf[NullaryMethodType]
            .resultType
        else containerName.symbol.tpe

      val classSymbol = containerSymbolType match {
        case TypeRef(_, classSymbol, _) => Some(classSymbol)
        // Object will be first
        case RefinedType(parents, decls) if parents.size > 1 =>
          parents.tail.headOption.map(_.typeSymbol)
        case _ => None
      }
      classSymbol match {
        case Some(classSymbol) =>
          // we get the position of the container
          // class because we want to add the method
          // definition there
          val containerClass =
            context.lookupSymbol(classSymbol.name, _ => true)
          // this gives me the position of the class
          typedTreeAt(containerClass.symbol.pos) match {
            // class / trait case
            case cls: ImplDef =>
              makeClassMethodEdits(cls.impl)
            case _ =>
              // object Y {}
              // Y.nonExistent(1,2,3)
              typedTreeAt(containerName.symbol.pos) match {
                // class / trait case
                case cls: ImplDef =>
                  makeClassMethodEdits(cls.impl)
                case _ =>
                  unimplemented(errorMethodName)
              }
          }
        case None =>
          unimplemented(errorMethodName)
      }
    } else {
      unimplemented(errorMethodName)
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
