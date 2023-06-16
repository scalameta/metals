package scala.meta.internal.pc.completions

import scala.meta.internal.pc.MetalsGlobal
import scala.meta.internal.semver.SemVer.Version

import org.eclipse.{lsp4j => l}

trait DependencyCompletions {
  this: MetalsGlobal =>

  abstract class DependencyCompletion extends CompletionPosition {
    override def compare(o1: Member, o2: Member): Int =
      (o1, o2) match {
        case (c1: DependecyMember, c2: DependecyMember)
            if c1.isVersion && c2.isVersion =>
          // For version completions, we want to show the latest version first
          Version.fromString(c2.label).compare(Version.fromString(c1.label))
        case _ => super.compare(o1, o2)
      }

    def makeMembers(
        completions: List[String],
        editRange: l.Range
    ): List[DependecyMember] =
      completions
        .map(insertText =>
          new DependecyMember(
            dependency = insertText,
            edit = new l.TextEdit(editRange, insertText)
          )
        )
  }

}
