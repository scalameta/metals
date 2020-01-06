package scala.meta.internal.pc

import org.eclipse.{lsp4j => l}

import scala.util.control.NonFatal
import scala.reflect.internal.ModifierFlags
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
      val necessaryIndent = inferIndent(pos, text)
      val indent = s"${necessaryIndent}${scaladocIndent}"

      val params: List[ValDef] = getParams(associatedDef)
      val cursor =
        if (clientSupportsSnippets) " $0" else ""

      val scaladocParamLines: String = params
        .map { param =>
          s"${indent}* @param ${param.name}"
        }
        .mkString("\n")

      // Add `* @return` only if the associatedDef is method definition.
      val returnLine =
        if (associatedDef.isInstanceOf[DefDef]) s"${indent}* @return" else ""

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
      val maybeEmptyLine =
        if (scaladocParamLines.isEmpty && returnLine.isEmpty) ""
        else s"${indent}*"
      val newText: String =
        s"""|${indent}*${cursor}
            |${maybeEmptyLine}
            |${scaladocParamLines}
            |${returnLine}
            |${indent}*/""".stripMargin
          .split("\n")
          .filter(!_.isEmpty) // remove empty lines: scaladocParamLines and returnLine can be empty
          .mkString("\n", "\n", "")

      List(
        new TextEditMember(
          "Scaladoc Comment",
          new l.TextEdit(
            editRange,
            newText
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
    private def inferIndent(pos: Position, text: String): String = {
      if (metalsConfig.snippetAutoIndent()) {
        ""
      } else {
        try {
          val line =
            text.split(System.lineSeparator())(pos.line - 1)
          line.takeWhile(ch => ch != '/')
        } catch { case NonFatal(_) => "" }
      }
    }

    /**
     * Returns the parameters of the given memberDef
     *
     * @param memberDef The memberDef to construct scaladoc.
     */
    private def getParams(memberDef: MemberDef): List[ValDef] = {
      memberDef match {
        case defdef: DefDef =>
          defdef.vparamss.flatten
        case clazz: ClassDef =>
          // If the associated def is a class definition,
          // retrieve the constructor from the class, and caluculate the lines
          // from the constructor definition instead.
          new ConstructorFinder(clazz).getConstructor match {
            case Some(defdef) => getParams(defdef)
            case scala.None => Nil
          }
        case _ => Nil
      }
    }

    class ConstructorFinder(clazz: ClassDef) extends Traverser {
      private var found: Option[DefDef] = scala.None
      def getConstructor: Option[DefDef] = {
        // Don't try to find the constructor for trait/abstract definition
        // because they don't have a constructor
        if (!clazz.mods.hasFlag(
            ModifierFlags.ABSTRACT |
              ModifierFlags.TRAIT |
              ModifierFlags.INTERFACE
          )) {
          found = scala.None
          clazz.impl.body.foreach(traverse)
          found
        } else {
          scala.None
        }
      }
      override def traverse(tree: Tree): Unit = {
        if (found
            .map(cur =>
              tree.pos.isDefined && cur.pos.isDefined && tree.pos.point <= cur.pos.point
            )
            .getOrElse(true)) {
          tree match {
            case constructor: DefDef
                if constructor.name == termNames.CONSTRUCTOR =>
              found = Some(constructor)
            case _ =>
              super.traverse(tree)
          }
        }
      }
    }
  }

  protected def isScaladocCompletion(pos: Position, text: String): Boolean = {
    try {
      val line =
        text.split(System.lineSeparator())(pos.line - 1)
      // check if the line starts with `/**`
      line.matches("^\\s*\\/\\*\\*\\s*$")
    } catch { case NonFatal(_) => false }
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
