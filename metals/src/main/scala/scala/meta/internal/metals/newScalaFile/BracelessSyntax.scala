package scala.meta.internal.metals.newScalaFile

import scala.annotation.tailrec

import scala.meta._
import scala.meta.tokens.{Token => T}

/**
 * Detects whether a Scala source prefers the optional-braces
 * (significant-indentation) style over curly braces.
 *
 * This lets `NewFileProvider` match the style of the surrounding project when
 * generating new files, instead of relying on a user setting.
 */
object BracelessSyntax {

  /**
   * Whether `tree` prefers braceless syntax, based on its first top-level type
   * definition that has a body.
   *
   *   - `Some(true)`  the first such definition uses significant indentation
   *   - `Some(false)` it uses curly braces
   *   - `None`        no top-level definition with a body was found
   */
  def prefersBraceless(tree: Tree): Option[Boolean] =
    topLevelTemplates(tree).flatMap(bodyStyle).nextOption()

  /** Templates of top-level type definitions, descending into packages. */
  private def topLevelTemplates(tree: Tree): Iterator[Template] =
    tree match {
      case Source(stats) => stats.iterator.flatMap(topLevelTemplates)
      case Pkg(_, stats) => stats.iterator.flatMap(topLevelTemplates)
      case t: Pkg.Object => Iterator.single(t.templ)
      case t: Defn.Class => Iterator.single(t.templ)
      case t: Defn.Trait => Iterator.single(t.templ)
      case t: Defn.Object => Iterator.single(t.templ)
      case t: Defn.Enum => Iterator.single(t.templ)
      case _ => Iterator.empty
    }

  /**
   * Whether a template body is opened by significant indentation rather than a
   * brace. We look for the first `{` or `:` at the top level of the template,
   * skipping anything nested in the parent constructor's parentheses/brackets
   * (and thus ignoring a member's own braces, which come after the opener).
   */
  private def bodyStyle(template: Template): Option[Boolean] = {
    @tailrec
    def loop(tokens: List[T], depth: Int): Option[Boolean] =
      tokens match {
        case Nil => None
        case (_: T.LeftParen | _: T.LeftBracket) :: rest =>
          loop(rest, depth + 1)
        case (_: T.RightParen | _: T.RightBracket) :: rest =>
          loop(rest, depth - 1)
        case (_: T.LeftBrace) :: _ if depth == 0 => Some(false)
        case (_: T.Colon) :: _ if depth == 0 => Some(true)
        case _ :: rest => loop(rest, depth)
      }
    loop(template.tokens.toList, 0)
  }
}
