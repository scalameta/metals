package scala.meta.internal.metals.mbt

import java.{util => ju}
import javax.annotation.Nullable

import scala.jdk.CollectionConverters._

case class MbtNamespace(
    @Nullable sources: ju.List[String],
    @Nullable scalacOptions: ju.List[String],
    @Nullable javacOptions: ju.List[String],
    @Nullable dependencyModules: ju.List[String] = ju.Collections.emptyList(),
    @Nullable scalaVersion: String,
    @Nullable javaHome: String,
    @Nullable dependsOn: ju.List[String] = null,
    @Nullable classDirectories: ju.List[String] = null,
    @Nullable testClassDirectory: ju.List[String] = null,
    @Nullable projectPath: String = null,
    @Nullable configurations: ju.List[String] = null,
) {
  def getSources: ju.List[String] =
    Option(this.sources).getOrElse(ju.Collections.emptyList())
  def getScalacOptions: ju.List[String] =
    Option(this.scalacOptions)
      .getOrElse(ju.Collections.emptyList())
  def getJavacOptions: ju.List[String] =
    Option(this.javacOptions)
      .getOrElse(ju.Collections.emptyList())
  def getDependencyModuleIds: ju.List[String] =
    Option(this.dependencyModules).getOrElse(ju.Collections.emptyList())
  def getDependsOn: ju.List[String] =
    Option(this.dependsOn).getOrElse(ju.Collections.emptyList())
  def getConfigurations: Seq[String] =
    Option(this.configurations)
      .map(_.asScala.toSeq)
      .getOrElse(Nil)
}
