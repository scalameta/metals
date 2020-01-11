package scala.meta.internal.pc

import java.lang.StringBuilder

import org.eclipse.{lsp4j => l}

import scala.util.control.NonFatal
import scala.collection.immutable.Nil

trait ScaladocCompletion { this: MetalsGlobal =>

  /**
   * A scaladoc completion showing the parameters of the given associated definition.
   *
   * @param editRange the range in the original source file.
   * @param associatedDef the memberDef associated with the scaladoc to complete.
   *                      This class will construct scaladoc based on the params of this definition.
   * @param pos the position of the completion request.
   * @param text the text of the original source code.
   */
  case class Scaladoc(
      editRange: l.Range,
      associatedDef: MemberDef,
      pos: Position,
      text: String
  ) extends CompletionPosition {
    // The indent for gutter asterisks aligned in column three.
    // |/**
    // |  *
    private val scaladocIndent = "  "

    override def contribute: List[Member] = {
      val necessaryIndent = inferIndent(pos)
      val indent = s"${necessaryIndent}${scaladocIndent}"
      val params: List[String] = getParams(associatedDef)

      // Construct the following new text.
      // """
      //
      //   * $0 <- move cursor here. Add an empty line below here, only if there's @param or @return line.
      //   *
      //   * @param param1
      //   * @param param2
      //   * @return
      //   */
      // """
      val builder = new StringBuilder()

      val hasConstructor = associatedDef.symbol.primaryConstructor.isDefined && !associatedDef.symbol.isAbstractClass
      val hasReturnValue =
        associatedDef.symbol.isMethod &&
          !(associatedDef.symbol.asMethod.returnType.finalResultType
            =:= typeOf[Unit])

      // newline after `/**`
      builder.append("\n")

      // * $0 <- move cursor here.
      builder.append(s"${indent}*")
      if (clientSupportsSnippets) builder.append(" $0\n")
      else builder.append("\n")

      // add empty line if there's parameter or "@return" or "@constructor" line.
      if (params.nonEmpty || hasReturnValue || hasConstructor)
        builder.append(s"${indent}*\n")

      // * @constructor
      if (hasConstructor) builder.append(s"${indent}* @constructor\n")

      // * @param p1
      // * @param p2
      params.foreach(param => builder.append(s"${indent}* @param ${param}\n"))

      // * @return
      // */
      if (hasReturnValue) builder.append(s"${indent}* @return\n")
      builder.append(s"${indent}*/")

      List(
        new TextEditMember(
          "Scaladoc Comment",
          new l.TextEdit(
            editRange,
            builder.toString()
          ),
          completionsSymbol(associatedDef.name.toString()),
          label = Some("/** */"),
          detail = Some("Scaladoc Comment")
        )
      )
    }

    // Infers the indentation at the completion position by counting the number of leading
    // spaces in the line.
    // For example:
    // |"""
    // |object A {
    // |  /**<COMPLETE> // inferred indent is 4 spaces
    // |  def foo(x: Int) = ???
    // |}
    // |"""
    private def inferIndent(pos: Position): String = {
      if (metalsConfig.snippetAutoIndent()) {
        ""
      } else {
        // 4 is the length of "/**$" <- $ is a cursor
        " " * (pos.column - 4)
      }
    }

    /**
     * Returns the parameter names of the given memberDef.
     *
     * @param memberDef The memberDef to construct scaladoc.
     */
    private def getParams(memberDef: MemberDef): List[String] = {
      memberDef match {
        case defdef: DefDef =>
          defdef.symbol.paramss.flatten
            .filterNot(_.isSynthetic)
            .map(_.name.toString())
        case clazz: ClassDef =>
          // If the associated def is a class definition,
          // retrieve the constructor from the class, and caluculate the lines
          // from the constructor definition instead.
          clazz.symbol.primaryConstructor.paramss.flatten
            .filterNot(sym =>
              // NOTE: We check the symbol name not starts with `EVIDENCE_PARAM_PREFIX`
              // to avoid line that `* @param evidence$1` in case the class constructor requires type class instance, like:
              // `case class Test[T: Ordering](t: T) = {}`
              // Those parameters are added by TreeBuilder here
              // https://github.com/scala/scala/blob/ba9701059216c629410f4f23a2175d20ad62484b/src/compiler/scala/tools/nsc/ast/parser/TreeBuilder.scala#L135
              //
              // Though the `SYNTHETIC` modifiers on DefDef survive during type checking,
              // the modifiers on class constructor attached in TreeBuilder seem not to remain
              // after type-checking. That's why check if the param name starts with `EVIDENCE_PARAM_PREFIX` for a class constructor.
              sym.isSynthetic ||
                sym.name.startsWith(nme.EVIDENCE_PARAM_PREFIX)
            )
            .map(_.name.toString())
        case _ => Nil
      }
    }
  }

  /**
   * Check if the cursor is located just behind the opening comment.
   *
   * @param pos The cursor position
   * @param text The original text of the source file.
   */
  protected def isScaladocCompletion(pos: Position, text: String): Boolean = {
    try {
      pos.isDefined &&
      text.charAt(pos.point - 3) == '/' &&
      text.charAt(pos.point - 2) == '*' &&
      text.charAt(pos.point - 1) == '*'
    } catch {
      case NonFatal(_) => false
    }
  }

  /**
   * Find a method definition right after the given position.
   *
   * @param pos The position of scaladoc in the original source.
   *            This class will find the associated member def based on this pos.
   */
  protected class AssociatedMemberDefFinder(pos: Position) extends Traverser {
    private var associatedDef: Option[MemberDef] = None

    /**
     * Collect all the member definitions whose position is
     * below the given `pos`. And then return the closest member definiton.
     */
    def findAssociatedDef(root: Tree): Option[MemberDef] = {
      associatedDef = None
      traverse(root)
      associatedDef
    }
    override def traverse(t: Tree): Unit = {
      t match {
        case typedef: TypeDef => process(typedef)
        case clsdef: ClassDef => process(clsdef)
        case defdef: DefDef => process(defdef)
        case moduledef: ModuleDef => process(moduledef)
        case pkgdef: PackageDef => process(pkgdef)
        case valdef: ValDef => process(valdef)
        case _ if treePos(t).includes(pos) => super.traverse(t)
        case _ =>
      }
    }
    private def process(t: MemberDef): Unit = {
      // if the node's location is below the current associate def,
      // we don't have to visit the node because it can't be an associated def.
      if (associatedDef
          .map(cur =>
            t.pos.isDefined && cur.pos.isDefined && t.pos.point <= cur.pos.point
          )
          .getOrElse(true)) {
        if (t.pos.isDefined && t.pos.start >= pos.start) associatedDef = Some(t)
        if (treePos(t).includes(pos)) super.traverse(t)
      }
    }
  }
}
