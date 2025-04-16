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
    (groupId, delim, artifactId, version, rest) <- sbtDirective match {
      case groupId :: Delim(delim) :: artifactId :: "%" :: version :: rest =>
        Some((groupId, delim, artifactId, version, rest))
      case _ => None
    }
    scalaCliDependencyIdentifier <- rest match {
      case Nil => Some(dependencyIdentifierLike)
      case List("%", Scope(dep)) => Some(dep)
      case _ => None
    }
  } yield {
    val groupArtifactJoin = delim.replace('%', ':')
    val millStyleDependency =
      s"$groupId$groupArtifactJoin$artifactId:$version".replace("\"", "")

    ReplacementSuggestion(scalaCliDependencyIdentifier, millStyleDependency)
  }

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

  /** @see https://www.scala-sbt.org/1.x/docs/Library-Dependencies.html#Per-configuration+dependencies */
  private object Scope {
    private val dependencyScope = Map(
      "test" -> "test.dep",
      "provided" -> "compileOnly.dep",
    )
    def unapply(scope: String): Option[String] =
      dependencyScope.get(scope.toLowerCase.replace("\"", ""))
  }

  case class ReplacementSuggestion(
      dependencyIdentifier: String,
      millStyleDependency: String,
  ) {
    val replacementDirective: String =
      s"//> using $dependencyIdentifier \"$millStyleDependency\""
  }
}
