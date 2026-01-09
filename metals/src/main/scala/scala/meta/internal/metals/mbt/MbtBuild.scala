package scala.meta.internal.metals.mbt

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.ArrayList
import java.{util => ju}
import javax.annotation.Nullable

import scala.jdk.CollectionConverters._

import scala.meta.internal.mtags
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j
import com.google.gson.Gson

case class MbtBuild(
    var dependencyModules: ju.List[MbtDependencyModule]
) {
  def asBsp: bsp4j.DependencyModulesResult =
    new bsp4j.DependencyModulesResult(
      List(
        new bsp4j.DependencyModulesItem(
          new bsp4j.BuildTargetIdentifier("mbt.json"),
          dependencyModules.asScala
            .map(m => {
              val module = new bsp4j.DependencyModule(m.id, m.version)
              val artifacts =
                new ArrayList[bsp4j.MavenDependencyModuleArtifact]()
              artifacts.add(
                new bsp4j.MavenDependencyModuleArtifact(m.jarUriString)
              )
              if (m.sources != null) {
                val sources =
                  new bsp4j.MavenDependencyModuleArtifact(
                    m.sourcesUriString.get
                  )
                sources.setClassifier("sources")
                artifacts.add(sources)
              }
              module.setDataKind(bsp4j.DependencyModuleDataKind.MAVEN)
              module.setData(
                new bsp4j.MavenDependencyModule(
                  m.organization,
                  m.name,
                  m.version,
                  artifacts,
                )
              )
              module
            })
            .asJava,
        )
      ).asJava
    )
  def asMtags: Seq[mtags.DependencyModule] =
    dependencyModules.asScala.iterator
      .map(module =>
        mtags.DependencyModule(
          mtags
            .MavenCoordinates(module.organization, module.name, module.version),
          AbsolutePath(module.jar),
          Option(module.sources).map(AbsolutePath(_)),
        )
      )
      .toSeq
}

object MbtBuild {
  val gson = new Gson()
  def empty: MbtBuild = MbtBuild(ju.Collections.emptyList())
  def fromWorkspace(workspace: AbsolutePath): MbtBuild =
    fromFile(workspace.resolve(".metals/mbt.json").toNIO)
  def fromFile(file: Path): MbtBuild = try {
    if (!Files.isRegularFile(file)) {
      return MbtBuild.empty
    }
    val text = Files.readString(file)
    val build = gson.fromJson(text, classOf[MbtBuild])
    val (validModules, invalidModules) =
      build.dependencyModules.asScala.partition(_.isValid)
    invalidModules.foreach { module =>
      scribe.warn(
        s"mbt-build: ignoring invalid dependency module ID '${module.id}'. Expected format: 'organization:name:version'."
      )
    }
    build.copy(dependencyModules = validModules.asJava)
  } catch {
    case e: Exception =>
      scribe.warn(s"Failed to parse MBT build from JSON file '$file'", e)
      MbtBuild.empty
  }
}
case class MbtDependencyModule(
    var id: String, // e.g. "com.google.guava:guava:30.0-jre"
    var jar: String,
    @Nullable var sources: String = null,
) {
  def jarPath: Path = Paths.get(jar)
  def jarUri: URI = Paths.get(jar).toUri
  def jarUriString: String = jarUri.toString
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
}
