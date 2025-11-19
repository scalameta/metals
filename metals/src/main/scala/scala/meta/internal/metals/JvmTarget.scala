package scala.meta.internal.metals

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.BuildTargetIdentifier

trait JvmTarget {

  def jvmHome: Option[String]

  def displayName: String

  def id: BuildTargetIdentifier

  /**
   * If the build server supports lazy classpath resolution, we will
   * not get any classpath data eagerly and we should not
   * use this endpoint. It should only be used as a fallback.
   *
   * This is due to the fact that we don't request classpath as it
   * can be resonably expensive.
   *
   * @return non empty classpath only if it was resolved prior
   */
  def classpath: Option[List[String]]

  def classDirectory: String

  /**
   * This method collects jars from classpath defined in scalacOptions.
   *
   * If the build server supports lazy classpath resolution, we will
   * not get any classpath data eagerly and we should not
   * use this endpoint. It should only be used as a fallback.
   *
   * This is due to the fact that we don't request classpath as it
   * can be resonably expensive.
   *
   * We should use the buildTargetDependencyModules information
   * from the indexer instead.
   *
   * @return non empty classpath jar list if it was resolved prior
   */
  def jarClasspath: Option[List[AbsolutePath]] =
    classpath.map(collectJars)

  private def collectJars(paths: List[String]) =
    paths
      .filter(_.endsWith(".jar"))
      .map(_.toAbsolutePath)
}
