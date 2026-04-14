package scala.meta.internal.metals.mbt

import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths
import java.util.ArrayList
import javax.annotation.Nullable

import ch.epfl.scala.bsp4j

case class MbtDependencyModule(
    @Nullable id: String, // e.g. "com.google.guava:guava:30.0-jre"
    @Nullable jar: String,
    @Nullable sources: String,
) {
  def jarPath: Option[Path] = Option(jar).map(Paths.get(_))
  def jarUri: Option[URI] = jarPath.map(_.toUri())
  def jarUriString: Option[String] = jarUri.map(_.toString)
  def sourcesUri: Option[URI] = Option(sources).map(Paths.get(_).toUri)
  def sourcesUriString: Option[String] = sourcesUri.map(_.toString)
  private def idParts: Array[String] = id.split(":", 3)
  def isValid: Boolean = idParts.length == 3
  def organization: String =
    idParts.lift(0).getOrElse(s"INVALID_ORGANIZATION=$id")
  def name: String =
    idParts.lift(1).getOrElse(s"INVALID_NAME=$id")
  def version: String =
    idParts.lift(2).getOrElse(s"INVALID_VERSION=$id")

  def asBsp: bsp4j.DependencyModule = {
    val module = new bsp4j.DependencyModule(id, version)
    val artifacts = new ArrayList[bsp4j.MavenDependencyModuleArtifact]()
    jarUriString.foreach { jarUri =>
      artifacts.add(new bsp4j.MavenDependencyModuleArtifact(jarUri))
    }
    sourcesUriString.foreach { sourceUri =>
      val source = new bsp4j.MavenDependencyModuleArtifact(sourceUri)
      source.setClassifier("sources")
      artifacts.add(source)
    }
    module.setDataKind(bsp4j.DependencyModuleDataKind.MAVEN)
    module.setData(
      new bsp4j.MavenDependencyModule(
        organization,
        name,
        version,
        artifacts,
      )
    )
    module
  }
}
