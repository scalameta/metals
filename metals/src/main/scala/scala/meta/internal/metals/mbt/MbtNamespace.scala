package scala.meta.internal.metals.mbt

import java.{util => ju}
import javax.annotation.Nullable

import scala.jdk.CollectionConverters._

// Every field defaults to its empty/absent value so a namespace can be built
// with only the attributes that actually apply (named arguments acting as the
// builder), instead of threading explicit empty lists through every call site.
case class MbtNamespace(
    @Nullable sources: ju.List[String] = null,
    @Nullable scalacOptions: ju.List[String] = null,
    @Nullable javacOptions: ju.List[String] = null,
    @Nullable dependencyModules: ju.List[String] = ju.Collections.emptyList(),
    @Nullable scalaVersion: String = null,
    @Nullable javaHome: String = null,
    @Nullable dependsOn: ju.List[String] = null,
    @Nullable classDirectories: ju.List[String] = null,
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
