package scala.meta.internal.metals.mbt

import java.{util => ju}
import javax.annotation.Nullable

case class MbtNamespace(
    @Nullable sources: ju.List[String],
    @Nullable compilerOptions: ju.List[String],
    @Nullable dependencyModules: ju.List[MbtDependencyModule] =
      ju.Collections.emptyList(),
    @Nullable scalaVersion: String,
    @Nullable javaHome: String,
    @Nullable dependsOn: ju.List[String] = null,
) {
  def getSources: ju.List[String] =
    Option(this.sources).getOrElse(ju.Collections.emptyList())
  def getCompilerOptions: ju.List[String] =
    Option(this.compilerOptions).getOrElse(ju.Collections.emptyList())
  def getDependencyModules: ju.List[MbtDependencyModule] =
    Option(this.dependencyModules).getOrElse(ju.Collections.emptyList())
  def getDependsOn: ju.List[String] =
    Option(this.dependsOn).getOrElse(ju.Collections.emptyList())
}
