package scala.meta.internal.metals

/**
 * This class handles packages that should be excluded from indexing causing
 * them to not be recommended for completions, symbol searches, and code actions.
 *
 * @param pkgsToExclude any exclusions that are in the `UserConfiguration` to start with.
 */
case class ExcludedPackagesHandler(exclusions: List[String]) {

  /**
   * Should the given package be excluded from indexing
   *
   * @param pkg package to check against excluded list
   */
  def isExcludedPackage(pkg: String): Boolean = {
    exclusions.exists(excluded => pkg.startsWith(excluded))
  }

}

object ExcludedPackagesHandler {

  val defaultExclusions: List[String] = List(
    "META-INF/", "images/", "toolbarButtonGraphics/", "jdk/", "sun/", "oracle/",
    "java/awt/desktop/", "org/jcp/", "org/omg/", "org/graalvm/", "com/oracle/",
    "com/sun/", "com/apple/", "apple/", "com/sourcegraph/shaded/"
  )

  val default: ExcludedPackagesHandler = ExcludedPackagesHandler(
    defaultExclusions
  )

  /**
   * @param packages list of packages in comma-delemited format: `com.example`
   */
  def fromUserConfiguration(packages: List[String]): ExcludedPackagesHandler =
    if (packages.isEmpty) default
    else {
      val all = packages.foldLeft(defaultExclusions) { (exclusions, pkg) =>
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
      ExcludedPackagesHandler(all)
    }
}
