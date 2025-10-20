package scala.meta.internal.pc.classpath

import java.net.URL

import scala.tools.nsc.{CloseableRegistry, Settings}
import scala.tools.nsc.util.ClassPath
import PartialFunction.condOpt
import scala.tools.nsc.classpath._
import scala.tools.util.PathResolver

/**
 * Copied from the Scala compiler and modified to allow passing a custom ClassPathFactory
 *
 * https://github.com/scala/scala/blob/v2.12.20/src/compiler/scala/tools/util/PathResolver.scala#L209
 */
final class MtagsPathResolver(
    settings: Settings,
    classPathFactory: ClassPathFactory,
    closeableRegistry: CloseableRegistry = new CloseableRegistry
) {

  import PathResolver.{AsLines, Defaults, ppcp}

  private def cmdLineOrElse(name: String, alt: String) = {
    (commandLineFor(name) match {
      case Some("") => None
      case x => x
    }) getOrElse alt
  }

  private def commandLineFor(s: String): Option[String] = condOpt(s) {
    case "javabootclasspath" => settings.javabootclasspath.value
    case "javaextdirs" => settings.javaextdirs.value
    case "bootclasspath" => settings.bootclasspath.value
    case "extdirs" => settings.extdirs.value
    case "classpath" | "cp" => settings.classpath.value
    case "sourcepath" => settings.sourcepath.value
  }

  /**
   * Calculated values based on any given command line options, falling back on
   *  those in Defaults.
   */
  object Calculated {
    def scalaHome = Defaults.scalaHome
    def useJavaClassPath = settings.usejavacp.value || Defaults.useJavaClassPath
    def useManifestClassPath = settings.usemanifestcp.value
    def javaBootClassPath =
      cmdLineOrElse("javabootclasspath", Defaults.javaBootClassPath)
    def javaExtDirs = cmdLineOrElse("javaextdirs", Defaults.javaExtDirs)
    def javaUserClassPath =
      if (useJavaClassPath) Defaults.javaUserClassPath else ""
    def scalaBootClassPath =
      cmdLineOrElse("bootclasspath", Defaults.scalaBootClassPath)
    def scalaExtDirs = cmdLineOrElse("extdirs", Defaults.scalaExtDirs)

    /**
     * Scaladoc doesn't need any bootstrapping, otherwise will create errors such as:
     * [scaladoc] ../scala-trunk/src/reflect/scala/reflect/macros/Reifiers.scala:89: error: object api is not a member of package reflect
     * [scaladoc] case class ReificationException(val pos: reflect.api.PositionApi, val msg: String) extends Throwable(msg)
     * [scaladoc]                                              ^
     * because the bootstrapping will look at the sourcepath and create package "reflect" in "<root>"
     * and then when typing relative names, instead of picking <root>.scala.relect, typedIdentifier will pick up the
     * <root>.reflect package created by the bootstrapping. Thus, no bootstrapping for scaladoc!
     * TODO: we should refactor this as a separate -bootstrap option to have a clean implementation, no?
     */
    def sourcePath = if (!settings.isScaladoc)
      cmdLineOrElse("sourcepath", Defaults.scalaSourcePath)
    else ""

    def userClassPath =
      settings.classpath.value // default is specified by settings and can be overridden there

    import classPathFactory._

    // Assemble the elements!
    def basis = List[Traversable[ClassPath]](
      if (
        settings.javabootclasspath.isSetByUser
      ) // respect explicit `-javabootclasspath rt.jar`
        Nil
      else
        jrt, // 0. The Java 9+ classpath (backed by the ct.sym or jrt:/ virtual system, if available)
      classesInPath(javaBootClassPath), // 1. The Java bootstrap class path.
      contentsOfDirsInPath(javaExtDirs), // 2. The Java extension class path.
      classesInExpandedPath(
        javaUserClassPath
      ), // 3. The Java application class path.
      classesInPath(scalaBootClassPath), // 4. The Scala boot class path.
      contentsOfDirsInPath(scalaExtDirs), // 5. The Scala extension class path.
      classesInExpandedPath(
        userClassPath
      ), // 6. The Scala application class path.
      classesInManifest(useManifestClassPath), // 8. The Manifest class path.
      sourcesInPath(sourcePath) // 7. The Scala source path.
    )

    private def jrt: List[ClassPath] = JrtClassPath.apply(
      settings.releaseValue,
      settings.unsafe.valueSetByUser,
      closeableRegistry
    )

    lazy val containers = basis.flatten.distinct

    override def toString = s"""
      |object Calculated {
      |  scalaHome            = $scalaHome
      |  javaBootClassPath    = ${ppcp(javaBootClassPath)}
      |  javaExtDirs          = ${ppcp(javaExtDirs)}
      |  javaUserClassPath    = ${ppcp(javaUserClassPath)}
      |  useJavaClassPath     = $useJavaClassPath
      |  scalaBootClassPath   = ${ppcp(scalaBootClassPath)}
      |  scalaExtDirs         = ${ppcp(scalaExtDirs)}
      |  userClassPath        = ${ppcp(userClassPath)}
      |  sourcePath           = ${ppcp(sourcePath)}
      |}""".asLines
  }

  def containers = Calculated.containers

  import PathResolver.MkLines

  def result: ClassPath = {
    val cp = computeResult()
    if (settings.Ylogcp) {
      Console print f"Classpath built from ${settings.toConciseString} %n"
      Console print s"Defaults: ${PathResolver.Defaults}"
      Console print s"Calculated: $Calculated"

      val xs = (Calculated.basis drop 2).flatten.distinct
      Console print (xs mkLines (
        s"After java boot/extdirs classpath has ${xs.size} entries:",
        indented = true
      ))
    }
    cp
  }

  def resultAsURLs: Seq[URL] = result.asURLs

  @deprecated("Use resultAsURLs instead of this one", "2.11.5")
  def asURLs: List[URL] = resultAsURLs.toList

  private def computeResult(): ClassPath = AggregateClassPath(
    containers.toIndexedSeq
  )
}
