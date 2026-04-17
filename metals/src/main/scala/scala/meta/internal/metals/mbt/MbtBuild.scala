package scala.meta.internal.metals.mbt

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.LinkedHashMap
import java.{util => ju}
import javax.annotation.Nullable

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j

case class MbtBuild(
    @Nullable dependencyModules: ju.List[MbtDependencyModule],
    @Nullable namespaces: ju.Map[String, MbtNamespace],
) {

  def getDependencyModules(): ju.List[MbtDependencyModule] =
    if (this.dependencyModules != null) this.dependencyModules
    else ju.Collections.emptyList()

  def getNamespaces: ju.Map[String, MbtNamespace] =
    if (this.namespaces != null) this.namespaces else ju.Collections.emptyMap()

  def isEmpty: Boolean =
    (dependencyModules == null || dependencyModules.isEmpty) &&
      (namespaces == null || namespaces.isEmpty)

  def asBspModules: bsp4j.DependencyModulesResult =
    new bsp4j.DependencyModulesResult(
      mbtTargets
        .filter(_.dependencyModules.nonEmpty)
        .map { target =>
          new bsp4j.DependencyModulesItem(
            target.id,
            target.dependencyModules.map(_.asBsp).asJava,
          )
        }
        .asJava
    )

  def mbtTargets: Seq[MbtTarget] =
    if (getNamespaces.isEmpty) {
      Option
        .when(!getDependencyModules().isEmpty) {
          MbtTarget(
            name = MbtBuild.LegacyTargetName,
            id = new bsp4j.BuildTargetIdentifier(MbtBuild.LegacyTargetName),
            sources = Nil,
            globMatchers = Nil,
            compilerOptions = Nil,
            dependencyModules = dependencyModules.asScala.toSeq,
          )
        }
        .toSeq
    } else {
      val knownNamespaces = getNamespaces.keySet.asScala.toSet
      getNamespaces.asScala.toSeq.map { case (name, namespace) =>
        val dependsOnIds =
          namespace.getDependsOn.asScala.toSeq.distinct.flatMap { depName =>
            if (knownNamespaces.contains(depName)) {
              Some(
                new bsp4j.BuildTargetIdentifier(
                  MbtBuild.namespaceTargetId(depName)
                )
              )
            } else {
              scribe.warn(
                s"mbt-build: namespace '$name' dependsOn unknown namespace '$depName'."
              )
              None
            }
          }
        val globPatterns = namespace.getSources.asScala.toSeq.filter(isGlob)
        val (validNsModules, invalidNsModules) =
          namespace.getDependencyModules.asScala.partition(_.isValid)
        invalidNsModules.foreach { module =>
          scribe.warn(
            s"mbt-build: ignoring invalid dependency module ID '${module.id}' in namespace '$name'. Expected format: 'organization:name:version'."
          )
        }
        MbtTarget(
          name = name,
          id =
            new bsp4j.BuildTargetIdentifier(MbtBuild.namespaceTargetId(name)),
          sources = namespace.getSources.asScala.toSeq
            .filterNot(isGlob),
          globMatchers = globPatterns.map(pattern =>
            MbtGlobMatcher(
              pattern = pattern,
              prefix = globPrefix(pattern),
              matcher = FileSystems.getDefault.getPathMatcher(
                "glob:" + globPatternForMatcher(pattern)
              ),
            )
          ),
          compilerOptions = namespace.getCompilerOptions.asScala.toSeq,
          dependencyModules = validNsModules.toSeq,
          scalaVersion = Option(namespace.scalaVersion),
          javaHome = Option(namespace.javaHome),
          dependsOn = dependsOnIds,
        )
      }
    }

  private def isGlob(pattern: String): Boolean = {
    val n = normalizeSlashes(pattern)
    n.exists(c => c == '*' || c == '?' || c == '[' || c == '{')
  }

  private def normalizeSlashes(s: String): String =
    s.trim.replace('\\', '/')

  /** Leading `./` is stripped so matchers align with workspace-relative paths. */
  private def globPatternForMatcher(pattern: String): String = {
    val n = normalizeSlashes(pattern)
    if (n.startsWith("./")) n.substring(2) else n
  }

  private def globPrefix(pattern: String): Option[Path] = {
    val literalSegments = globPatternForMatcher(pattern)
      .split('/')
      .toSeq
      .filter(_.nonEmpty)
      .takeWhile(segment => !isGlob(segment))
    literalSegments match {
      case head +: tail => Some(Paths.get(head, tail: _*))
      case _ => None
    }
  }
}

object MbtBuild {
  private val gson = new com.google.gson.Gson()
  private val gsonPretty =
    new com.google.gson.GsonBuilder().setPrettyPrinting().create()
  val LegacyTargetName = "default"

  def toJson(build: MbtBuild): String = gsonPretty.toJson(build)

  def empty: MbtBuild =
    MbtBuild(
      ju.Collections.emptyList(),
      new LinkedHashMap[String, MbtNamespace](),
    )

  def fromWorkspace(workspace: AbsolutePath): MbtBuild =
    fromFile(workspace.resolve(".metals/mbt.json").toNIO)

  def fromFile(file: Path): MbtBuild = try {
    if (!Files.isRegularFile(file)) {
      return MbtBuild.empty
    }
    val text = Files.readString(file)
    val build = gson.fromJson(text, classOf[MbtBuild])
    val (validModules, invalidModules) =
      build.getDependencyModules.asScala.partition(_.isValid)
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

  def namespaceTargetId(name: String): String = {
    s"mbt://namespace/$name"
  }

  /**
   * Merge two [[MbtBuild]] values produced by different importers.
   *
   * - `dependencyModules` are unioned and deduplicated by `id`.
   * - `namespaces` maps are merged; when the same key exists in both builds,
   *   the value from `other` wins and a warning is logged.
   */
  def merge(a: MbtBuild, b: MbtBuild): MbtBuild = {
    val mergedModules =
      (a.getDependencyModules.asScala ++ b.getDependencyModules.asScala)
        .distinctBy(_.id)
        .asJava

    val mergedNamespaces =
      new LinkedHashMap[String, MbtNamespace](a.getNamespaces)
    b.getNamespaces.asScala.foreach { case (key, ns) =>
      if (mergedNamespaces.containsKey(key))
        scribe.warn(
          s"mbt-merge: namespace '$key' is defined by multiple importers; last one wins."
        )
      mergedNamespaces.put(key, ns)
    }

    MbtBuild(mergedModules, mergedNamespaces)
  }

}
