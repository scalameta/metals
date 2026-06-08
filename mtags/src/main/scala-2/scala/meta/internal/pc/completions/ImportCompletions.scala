package scala.meta.internal.pc.completions

import scala.meta.internal.pc.MetalsGlobal

trait ImportCompletions { this: MetalsGlobal =>

  /**
   * True when the cursor sits on an import *prefix* (`import Inner@@`) rather
   * than on a selector (`import a.Outer.Inner@@`, `import a.{Inner@@}`).
   *
   * Decided from the source text, since how each Scala version's parser shapes
   * an incomplete import tree (member vs scope, qualifier position) is not
   * stable across 2.11/2.12/2.13. A prefix has no qualifier separator (`.` or
   * `{`) between the start of the statement and the cursor.
   */
  def isImportPrefixPosition(pos: Position, text: String): Boolean = {
    val uptoCursor = text.substring(0, math.min(pos.point, text.length))
    val statementStart =
      math.max(uptoCursor.lastIndexOf('\n'), uptoCursor.lastIndexOf(';')) + 1
    val segment = uptoCursor.substring(statementStart)
    !segment.contains('.') && !segment.contains('{')
  }

  /**
   * A completion on an import prefix, example `import Inner@@`.
   *
   * Only used when the cursor is on the import prefix (a scope completion);
   * selector completions such as `import a.Outer.Inner@@` are handled by the
   * default member completion, see `completionPositionUnsafe`.
   */
  case class ImportCompletion(pos: Position) extends CompletionPosition {
    // context at the import position (includes preceding imports in the block)
    private lazy val importContext = doLocateImportContext(pos)

    override def isCandidate(member: Member): Boolean = {
      val sym = member.sym
      // Keep packages, objects/modules and anything not already in scope.
      // Drop only *pure types* (class/trait/type alias) that are already in
      // scope, re-importing those is redundant. Objects/packages stay because
      // you may still select a member (`import Inner.A`).
      sym.hasPackageFlag ||
      !sym.isType ||
      sym.isModuleClass ||
      !importContext.symbolIsInScope(sym)
    }
  }

}
