package scala.meta.internal.metals

/**
 * This class handles packages that should be excluded from indexing causing
 * them to not be recommended for completions, symbol searches, and code actions.
 *
 * @param userConfig access to user input for excluded packages
 */
class ExcludedPackagesHandler(userConfig: () => UserConfiguration) {
  val defaultExclusions: List[String] = List(
    "META-INF/", "images/", "toolbarButtonGraphics/", "jdk/", "sun/", "javax/",
    "oracle/", "java/awt/desktop/", "org/jcp/", "org/omg/", "org/graalvm/",
    "com/oracle/", "com/sun/", "com/apple/", "apple/"
  )

  private def handleUserExclusions(pkgs: List[String]): List[String] = {
    pkgs.foldRight(defaultExclusions)((pkg, acc) => {
      val replaced = pkg.replace(".", "/")
      val formatted =
        if (replaced.endsWith("/")) replaced
        else replaced + "/"

      if (formatted.startsWith("--")) {
        acc.filterNot(_.equals(formatted.drop(2)))
      } else {
        formatted :: acc
      }
    })
  }

  /**
   * Should the given package be excluded from indexing
   *
   * @param pkg package to check against excluded list
   */
  def isExcludedPackage(pkg: String): Boolean = {
    val packagesToExclude: List[String] = userConfig().excludedPackages match {
      case Some(pkgs) => handleUserExclusions(pkgs)
      case None => defaultExclusions
    }
    packagesToExclude.exists(excluded => pkg.startsWith(excluded))
  }
}
