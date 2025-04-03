package scala.meta.internal.metals.scalacli

object DependencyConverter {
  def convertSbtToMillStyleIfPossible(
      sbtStyleDirective: String
  ): Option[ReplacementSuggestion] = for {
    (dependencyIdentifierLike, sbtDirective) <-
      raw"\s+".r.split(sbtStyleDirective).toList match {
        case "//>" :: "using" :: dependencyIdentifier :: rest
            if dependencyIdentifiers(dependencyIdentifier) =>
          Some(dependencyIdentifier -> rest)
        case "//>" :: "using" :: rest => Some("dep" -> rest)
        case _ => None
      }
    suggestion <- sbtDirective match {
      case List(groupId, groupDelimiter, artifactId, "%", version)
          if sbtDependencyDelimiters(groupDelimiter) =>
        val groupArtifactJoin = groupDelimiter.replace('%', ':')
        val millStyleDependency =
          s"$groupId$groupArtifactJoin$artifactId:$version".replace("\"", "")
        Some(
          ReplacementSuggestion(dependencyIdentifierLike, millStyleDependency)
        )
      case _ => None
    }
  } yield suggestion

  private val dependencyIdentifiers = Set("dep", "test.dep", "lib", "plugin")
  private val sbtDependencyDelimiters = Set("%", "%%", "%%%")

  case class ReplacementSuggestion(
      dependencyIdentifier: String,
      millStyleDependency: String,
  ) {
    val replacementDirective: String =
      s"//> using $dependencyIdentifier \"$millStyleDependency\""
  }
}
