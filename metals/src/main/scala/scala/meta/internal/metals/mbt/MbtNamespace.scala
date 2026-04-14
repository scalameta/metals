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
    if (this.sources != null) this.sources else ju.Collections.emptyList()
  def getCompilerOptions: ju.List[String] =
    if (this.compilerOptions != null) this.compilerOptions
    else ju.Collections.emptyList()
  def getDependencyModules: ju.List[MbtDependencyModule] =
    if (this.dependencyModules != null) this.dependencyModules
    else ju.Collections.emptyList()
  def getDependsOn: ju.List[String] =
    if (this.dependsOn != null) this.dependsOn else ju.Collections.emptyList()
}
