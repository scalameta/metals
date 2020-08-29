package scala.meta.internal.metals

import scala.collection.mutable.ListBuffer

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

  /**
   * Cached exclusions to make sure that we only have to process the list
   * once. This is only cleared if the user changes the exclusions in the
   * UserConfiguration which is detected in the `workspace/didChangeConfiguration`.
   */
  private lazy val cachedExclusions: ListBuffer[String] = new ListBuffer

  private def handleUserExclusions(pkgs: List[String]): List[String] = {
    defaultExclusions.foreach(cachedExclusions += _)
    pkgs.foreach { pkg =>
      val replaced = pkg.replace(".", "/")
      val formatted =
        if (replaced.endsWith("/")) replaced
        else replaced + "/"

      if (formatted.startsWith("--")) {
        cachedExclusions -= formatted.drop(2)
      } else {
        cachedExclusions += formatted
      }
    }
    cachedExclusions.result
  }

  def clearExclusionsCache(): Unit = cachedExclusions.clear()

  /**
   * Should the given package be excluded from indexing
   *
   * @param pkg package to check against excluded list
   */
  def isExcludedPackage(pkg: String): Boolean = {
    val packagesToExclude: List[String] = userConfig().excludedPackages match {
      case Some(pkgs) =>
        if (cachedExclusions.nonEmpty) cachedExclusions.result
        else handleUserExclusions(pkgs)
      case None => defaultExclusions
    }
    packagesToExclude.exists(excluded => pkg.startsWith(excluded))
  }
}
