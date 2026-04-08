package scala.meta.internal.metals.scalacli

object DependencyConverter {
  def convertSbtToMillStyleIfPossible(
      sbtStyleDirective: String
  ): Option[ReplacementSuggestion] =
    sbtStyleDirective.split(" ").filterNot(_.isEmpty) match {
      case Array(
            "//>",
            "using",
            dependencyIdentifierLike,
            groupId,
            groupDelimiter,
            artifactId,
            "%",
            version,
          )
          if dependencyIdentifiers(dependencyIdentifierLike) &&
            sbtDependencyDelimiters(groupDelimiter) =>
        val groupArtifactJoin = groupDelimiter.replace('%', ':')
        val millStyleDependency =
          s"$groupId$groupArtifactJoin$artifactId:$version".replace("\"", "")
        Some(
          ReplacementSuggestion(dependencyIdentifierLike, millStyleDependency)
        )
      case _ => None
    }

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
