package scala.meta.internal.metals.mbt

import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths
import java.util.ArrayList
import javax.annotation.Nullable

import scala.util.Try

import ch.epfl.scala.bsp4j

case class MbtDependencyModule(
    @Nullable id: String, // e.g. "com.google.guava:guava:30.0-jre"
    @Nullable jar: String, // URI string, e.g. "file:///path/to/jar.jar"
    @Nullable sources: String, // URI string, e.g. "file:///path/to/jar-sources.jar"
) {

  @transient lazy val jarUri: Option[URI] =
    Option(jar).map(MbtDependencyModule.parseUri)
  @transient lazy val jarPath: Option[Path] = jarUri.map(Paths.get)
  @transient lazy val sourcesURI: Option[URI] =
    Option(sources).map(MbtDependencyModule.parseUri)
  @transient private lazy val idParts: Array[String] = id.split(":", 3)
  @transient lazy val isValid: Boolean = idParts.length > 0
  @transient lazy val organization: String =
    idParts.lift(0).getOrElse(s"INVALID_ORGANIZATION=$id")
  @transient lazy val name: String =
    idParts.lift(1).getOrElse(s"INVALID_NAME=$id")
  @transient lazy val version: String =
    idParts.lift(2).getOrElse(s"INVALID_VERSION=$id")

  @transient lazy val asBsp: bsp4j.DependencyModule = {
    val module = new bsp4j.DependencyModule(id, version)
    val artifacts = new ArrayList[bsp4j.MavenDependencyModuleArtifact]()
    jarUri.foreach { jarUri =>
      artifacts.add(new bsp4j.MavenDependencyModuleArtifact(jarUri.toString))
    }
    sourcesURI.foreach { sourceUri =>
      val source = new bsp4j.MavenDependencyModuleArtifact(sourceUri.toString)
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

object MbtDependencyModule {

  /**
   * Parse a string that should be a URI but may accidentally be a file path.
   */
  private def parseUri(value: String): URI =
    Try(URI.create(value)).toOption
      .filterNot(_.getScheme == null)
      .getOrElse(Paths.get(value).toUri)

}
