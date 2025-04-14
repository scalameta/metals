package scala.meta.internal.metals.scalacli

object DependencyConverter {
  def convertSbtToMillStyleIfPossible(
      sbtStyleDirective: String
  ): Option[ReplacementSuggestion] = for {
    (dependencyIdentifierLike, sbtDirective) <-
      raw"\s+".r.split(sbtStyleDirective).toList match {
        case "//>" :: "using" :: Dep(dep) :: rest => Some(dep -> rest)
        case "//>" :: "using" :: rest => Some("dep" -> rest)
        case _ => None
      }
    suggestion <- sbtDirective match {
      case List(groupId, Delim(delim), artifactId, "%", version) =>
        val groupArtifactJoin = delim.replace('%', ':')
        val millStyleDependency =
          s"$groupId$groupArtifactJoin$artifactId:$version".replace("\"", "")
        Some(
          ReplacementSuggestion(dependencyIdentifierLike, millStyleDependency)
        )
      case _ => None
    }
  } yield suggestion

  /** scala-cli style dependency identifiers */
  private object Dep {
    private val dependencyIdentifiers = Set("dep", "test.dep", "lib", "plugin")
    def unapply(identifier: String): Option[String] =
      Option.when(dependencyIdentifiers(identifier))(identifier)
  }

  /** SBT-style dependency delimiters */
  private object Delim {
    private val sbtDependencyDelimiters = Set("%", "%%", "%%%")
    def unapply(delimiter: String): Option[String] =
      Option.when(sbtDependencyDelimiters(delimiter))(delimiter)
  }

  case class ReplacementSuggestion(
      dependencyIdentifier: String,
      millStyleDependency: String,
  ) {
    val replacementDirective: String =
      s"//> using $dependencyIdentifier \"$millStyleDependency\""
  }
}
