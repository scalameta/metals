package scala.meta.internal.metals

/**
 * This class handles packages that should be excluded from indexing causing
 * them to not be recommended for completions, symbol searches, and code actions.
 *
 * @param pkgsToExclude any exclusions that are in the `UserConfiguration` to start with.
 */
class ExcludedPackagesHandler(pkgsToExclude: Option[List[String]] = None) {
  val defaultExclusions: List[String] = List(
    "META-INF/", "images/", "toolbarButtonGraphics/", "jdk/", "sun/", "oracle/",
    "java/awt/desktop/", "org/jcp/", "org/omg/", "org/graalvm/", "com/oracle/",
    "com/sun/", "com/apple/", "apple/"
  )

  /**
   * Cached exclusions to make sure that we only have to process the list
   * once. This is only updated if the user changes the exclusions in the
   * UserConfiguration which is detected in the `workspace/didChangeConfiguration`.
   */
  private var cachedExclusions: List[String] = List.empty

  /**
   * More than likely a user will give us exclusions in the following format:
   *
   * - `akka.actor.typed.javadsl`
   *
   * This will make sure that they are always formatted in the way we need them
   * to be when we are actually comparing them as paths:
   *
   * - `akka/actor/typed/javadsl/`
   *
   * @param pkgs pkgs to either add to or check against the defaults.
   */
  private def prepareCache(pkgs: List[String]): List[String] = {
    pkgs.foldLeft(defaultExclusions) { (exclusions, pkg) =>
      val replaced = pkg.replace(".", "/")
      val formatted =
        if (replaced.endsWith("/")) replaced
        else replaced + "/"

      if (formatted.startsWith("--")) {
        exclusions.filterNot(_ == formatted.drop(2))
      } else {
        formatted :: exclusions
      }
    }
  }

  def update(pkgs: Option[List[String]]): Unit =
    cachedExclusions = pkgs match {
      case Some(newPkgs) => prepareCache(newPkgs)
      case None => defaultExclusions
    }

  /**
   * Should the given package be excluded from indexing
   *
   * @param pkg package to check against excluded list
   */
  def isExcludedPackage(pkg: String): Boolean = {
    if (cachedExclusions.nonEmpty) {
      cachedExclusions.exists(excluded => pkg.startsWith(excluded))
    } else {
      val exclusions = pkgsToExclude match {
        case Some(pkgs) =>
          val toExclude = prepareCache(pkgs)
          cachedExclusions = toExclude
          toExclude
        case None => defaultExclusions
      }
      exclusions.exists(excluded => pkg.startsWith(excluded))
    }
  }
}
