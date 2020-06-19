package scala.meta.internal.pc.completions

import java.net.URI
import java.nio.file.Paths

import scala.collection.immutable.Nil
import scala.util.control.NonFatal

import scala.meta.internal.pc.CompletionFuzzy
import scala.meta.internal.pc.MetalsGlobal

import org.eclipse.{lsp4j => l}

trait FilenameCompletions { this: MetalsGlobal =>

  /**
   * Completion for the name of a toplevel class, trait or object matching the filename.
   *
   * Example: {{{
   *   // src/main/scala/app/UserDatabaseService.scala
   *   class User@@ // completes "UserDatabaseService"
   * }}}
   *
   * @param toplevel the toplevel class, trait or object definition.
   * @param pkg the enclosing package definition.
   * @param pos the completion position.
   * @param editRange the range to replace in the completion.
   */
  case class FilenameCompletion(
      toplevel: DefTree,
      pkg: PackageDef,
      pos: Position,
      editRange: l.Range
  ) extends CompletionPosition {
    val query: String = toplevel.name.toString().stripSuffix(CURSOR)
    override def contribute: List[Member] = {
      try {
        val name = Paths
          .get(URI.create(pos.source.file.name))
          .getFileName()
          .toString()
          .stripSuffix(".scala")
        val isTermName = toplevel.name.isTermName
        val siblings = pkg.stats.count {
          case d: DefTree =>
            d.name.toString() == name &&
              d.name.isTermName == isTermName
          case _ => false
        }
        if (
          !name.isEmpty &&
          CompletionFuzzy.matches(query, name) &&
          siblings == 0
        ) {
          List(
            new TextEditMember(
              name,
              new l.TextEdit(editRange, name),
              toplevel.symbol
                .newErrorSymbol(TermName(name))
                .setInfo(NoType),
              label = Some(s"${toplevel.symbol.keyString} ${name}")
            )
          )
        } else {
          Nil
        }
      } catch {
        case NonFatal(e) =>
          Nil
      }
    }
  }

}
