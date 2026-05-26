package tests

import java.util.LinkedHashMap

import scala.jdk.CollectionConverters._

import scala.meta.internal.metals.mbt.MbtBuild
import scala.meta.internal.metals.mbt.MbtDependencyModule
import scala.meta.internal.metals.mbt.MbtNamespace

import coursierapi.Dependency
import coursierapi.Fetch

/** Builder for generating MBT JSON configuration for tests. */
case class MbtJsonBuilder(
    scalaVersion: String,
    dependencyModules: List[MbtDependencyModule] = List.empty,
    namespaces: List[(String, MbtNamespace)] = List.empty,
) {

  private val scalaBinaryVersion: String =
    scalaVersion.split("\\.").take(2).mkString(".")

  def addScalaLibrary(): MbtJsonBuilder = {
    val jarUri = Library.getScalaLibraryJarPath(scalaVersion).toURI.toString
    val id = s"org.scala-lang:scala-library:$scalaVersion"
    this.copy(dependencyModules =
      MbtDependencyModule(id, jarUri, null) :: dependencyModules
    )
  }

  def addDependency(
      org: String,
      name: String,
      version: String,
  ): MbtJsonBuilder = {
    val jars = Fetch
      .create()
      .withDependencies(
        Dependency.of(org, s"${name}_$scalaBinaryVersion", version)
      )
      .fetch()
      .asScala
    val deps = for (file <- jars) yield {
      val path = file.toPath
      val jarVersion = path.getParent.getFileName.toString
      val jarName = path.getFileName.toString
      val jarOrg = path.getParent.getParent.getFileName.toString
      val id = s"$jarOrg:$jarName:$jarVersion"
      val jarUri = path.toUri.toString
      MbtDependencyModule(id, jarUri, null)
    }
    this.copy(dependencyModules = deps.toList)
  }

  def addJavaDependency(
      org: String,
      name: String,
      version: String,
  ): MbtJsonBuilder = {
    val jars = Fetch
      .create()
      .withDependencies(Dependency.of(org, name, version))
      .fetch()
      .asScala
    val deps = for (file <- jars) yield {
      val path = file.toPath
      val jarVersion = path.getParent.getFileName.toString
      val jarName = path.getFileName.toString
      val jarOrg = path.getParent.getParent.getFileName.toString
      val id = s"$jarOrg:$jarName:$jarVersion"
      val jarUri = path.toUri.toString
      MbtDependencyModule(id, jarUri, null)
    }
    this.copy(dependencyModules = deps.toList)
  }

  def addNamespace(
      name: String,
      sources: List[String],
      dependsOn: List[String] = Nil,
      customScalaVersion: Option[String] = None,
  ): MbtJsonBuilder = {
    val distinctDeps = dependencyModules.distinctBy(_.id).reverse
    val namespace = MbtNamespace(
      sources = sources.asJava,
      scalacOptions = null,
      javacOptions = null,
      dependencyModules = distinctDeps.map(_.id).asJava,
      scalaVersion = customScalaVersion.getOrElse(scalaVersion),
      javaHome = null,
      dependsOn = if (dependsOn.isEmpty) null else dependsOn.asJava,
    )
    this.copy(namespaces = (name -> namespace) :: namespaces)
  }

  def build(): String = {
    val distinctDeps = dependencyModules.distinctBy(_.id).reverse
    val namespacesMap = new LinkedHashMap[String, MbtNamespace]()
    for ((name, ns) <- namespaces.reverse) {
      val updatedNs = ns.copy(
        dependencyModules = distinctDeps.map(_.id).asJava
      )
      namespacesMap.put(name, updatedNs)
    }

    val mbtBuild = MbtBuild(
      dependencyModules = distinctDeps.asJava,
      namespaces = namespacesMap,
    )
    MbtBuild.toJson(mbtBuild)
  }

  def allDependencyIds: List[String] =
    dependencyModules.distinctBy(_.id).reverse.map(_.id)
}
