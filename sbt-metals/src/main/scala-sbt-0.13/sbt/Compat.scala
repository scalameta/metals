package sbt

object Compat {

  // Not available in sbt 0.13, fallback to `append`.
  def appendWithSession(
      extracted: Extracted,
      settings: Seq[Setting[_]],
      s: State
  ): State = {
    extracted.append(settings, s)
  }
}
