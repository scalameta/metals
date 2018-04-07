package sbt

import sbt.internal.Load

object Compat {

  // Copy-pasted `appendWithSession` from sbt, available only in sbt 1.1.
  def appendWithSession(
      extracted: Extracted,
      settings: Seq[Setting[_]],
      state: State
  ): State =
    appendImpl(extracted, settings, state, extracted.session.mergeSettings)

  private[this] def appendImpl(
      extracted: Extracted,
      settings: Seq[Setting[_]],
      state: State,
      sessionSettings: Seq[Setting[_]],
  ): State = {
    import extracted.{currentRef, rootProject, session, showKey, structure}
    val appendSettings =
      Load.transformSettings(
        Load.projectScope(currentRef),
        currentRef.build,
        rootProject,
        settings
      )
    val newStructure =
      Load.reapply(sessionSettings ++ appendSettings, structure)
    Project.setProject(session, newStructure, state)
  }
}
