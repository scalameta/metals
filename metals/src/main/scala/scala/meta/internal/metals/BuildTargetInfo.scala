package scala.meta.internal.metals

import java.nio.file.Path

import scala.collection.mutable.ListBuffer

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.BuildTarget

class BuildTargetInfo(buildTargets: BuildTargets) {

  def buildTargetDetails(targetName: String): String = {
    buildTargets.all
      .filter(_.getDisplayName == targetName)
      .map(_.getId())
      .headOption
      .flatMap(buildTargetDetail)
      .getOrElse(s"Build target $targetName not found")
  }

  private def buildTargetDetail(
      targetId: BuildTargetIdentifier
  ): Option[String] = {
    extractTargetDetail(targetId).map(detail => {
      val info = ListBuffer[String]()
      info += "Target"
      info += s"  ${detail.common.displayName}"
      if (detail.common.tags.nonEmpty) {
        info += ""
        info += "Tags"
        detail.common.tags.foreach(f => info += s"  $f")
      }
      if (detail.common.languageIds.nonEmpty) {
        info += ""
        info += "Languages"
        detail.common.languageIds.foreach(f => info += s"  $f")
      }
      if (detail.common.capabilities.nonEmpty) {
        info += ""
        info += "Capabilities"
        detail.common.capabilities.foreach(f =>
          info += { if (f._2) s"  ${f._1}" else s"  ${f._1} <- NOT SUPPORTED" }
        )
      }
      if (detail.common.dependencies.nonEmpty) {
        info += ""
        info += "Dependencies"
        detail.common.dependencies.foreach(f => info += s"  $f")
      }
      if (detail.common.dependentTargets.nonEmpty) {
        info += ""
        info += "Dependent Targets"
        detail.common.dependentTargets.foreach(f => info += s"  $f")
      }
      detail.java.foreach(java => {
        info += ""
        info += "Javac Options"
        info += "  compile - https://docs.oracle.com/en/java/javase/17/docs/specs/man/javac.html#options"
        info += "  runtime - https://docs.oracle.com/en/java/javase/17/docs/specs/man/java.html#standard-options-for-java"
        info += "  "
        if (java.options.nonEmpty)
          java.options.foreach(f =>
            info += s"  ${if (f.isEmpty) "[BLANK]" else f}"
          )
        else
          info += "  [BLANK]"
      })
      detail.scala.foreach(scala => {
        info += ""
        info += "Scalac Options"
        if (scala.scalaBinaryVersion.startsWith("3"))
          info += "  compile - https://docs.scala-lang.org/scala3/guides/migration/options-new.html"
        else
          info += "  compile - https://docs.scala-lang.org/overviews/compiler-options/index.html#Standard_Settings"
        info += "  "
        if (scala.options.nonEmpty)
          scala.options.foreach(f =>
            info += s"  ${if (f.isEmpty) "[BLANK]" else f}"
          )
        else
          info += "  [BLANK]"

        info += ""
        info += "Scala Version"
        info += s"  ${scala.scalaVersion}"
        info += ""
        info += "Scala Binary Version"
        info += s"  ${scala.scalaBinaryVersion}"
        info += ""
        info += "Scala Platform"
        info += s"  ${scala.scalaPlatform}"
        scala.jvmVersion.foreach(jvmVersion => {
          info += ""
          info += "JVM Version"
          info += s"  $jvmVersion"
        })
        scala.jvmHome.foreach(jvmHome => {
          info += ""
          info += "JVM Home"
          info += s"  $jvmHome"
        })
      })
      info += ""
      info += "Base Directory"
      info += s"  ${detail.common.baseDirectory}"
      info += ""
      info += "Source Directories"
      if (detail.common.sources.nonEmpty)
        detail.common.sources.foreach(f => info += s"  $f")
      else
        info += "  NONE"
      if (detail.sameJavaScalaClassesDir)
        detail.java.foreach(java => {
          info += ""
          info += "Classes Directory"
          info += s"  ${java.classesDirectory}"
        })
      else {
        detail.java.foreach(java => {
          info += ""
          info += "Java Classes Directory"
          info += s"  ${java.classesDirectory}"
        })
        detail.scala.foreach(scala => {
          info += ""
          info += "Scala Classes Directory"
          info += s"  ${scala.classesDirectory}"
        })
      }
      if (detail.sameJavaScalaClasspaths)
        detail.java.foreach(java => {
          info += ""
          info += "Classpath"
          addClassPath(info, java.classPath)
        })
      else {
        detail.java.foreach(java => {
          info += ""
          info += "Java Classpath"
          addClassPath(info, java.classPath)
        })
        detail.scala.foreach(scala => {
          info += ""
          info += "Scala Classpath"
          addClassPath(info, scala.classPath)
        })
      }
      info += ""
      info.mkString(System.lineSeparator())
    })
  }

  private def addClassPath(
      info: ListBuffer[String],
      classPath: Iterable[Path]
  ): Unit = {
    def convertToName(path: Path): Path = {
      if (path.toFile.isFile)
        path.getFileName()
      else
        path
    }
    if (classPath.nonEmpty) {
      val maxFilenameSize =
        classPath.map(convertToName(_).toString.length()).max + 5
      classPath.foreach(f => {
        val filename = convertToName(f).toString()
        val padding = " " * (maxFilenameSize - filename.size)
        val status = if (f.toFile.exists) {
          if (f.toFile().isDirectory())
            "         "
          else {
            val sourceJarName = filename.replace(".jar", "-sources.jar")
            val hasSource = buildTargets
              .sourceJarFile(sourceJarName)
              .exists(_.toFile.exists())
            if (hasSource)
              "         "
            else
              "NO SOURCE"
          }
        } else " MISSING "
        val fullName = if (f.toFile.isFile) s" $f" else ""
        info += s"  $filename$padding $status$fullName"
      })
    } else
      info += "  NONE"
  }

  private case class TargetDetail(
      common: CommonTargetDetail,
      java: Option[JavaTargetDetail],
      scala: Option[ScalaTargetDetail]
  ) {
    def sameJavaScalaClasspaths: Boolean =
      java.nonEmpty && scala.nonEmpty && java.get.classPath == scala.get.classPath
    def sameJavaScalaClassesDir: Boolean =
      java.nonEmpty && scala.nonEmpty && java.get.classesDirectory == scala.get.classesDirectory
  }
  private case class CommonTargetDetail(
      id: BuildTargetIdentifier,
      displayName: String,
      baseDirectory: String,
      tags: Iterable[String],
      languageIds: Iterable[String],
      dependencies: Iterable[String],
      dependentTargets: Iterable[String],
      capabilities: Iterable[(String, Boolean)],
      sources: Iterable[AbsolutePath]
  )
  private case class JavaTargetDetail(
      classesDirectory: String,
      classPath: Iterable[Path],
      options: Iterable[String]
  )
  private case class ScalaTargetDetail(
      classesDirectory: String,
      classPath: Iterable[Path],
      options: Iterable[String],
      scalaVersion: String,
      scalaBinaryVersion: String,
      scalaPlatform: String,
      jvmVersion: Option[String],
      jvmHome: Option[String]
  )
  private def extractTargetDetail(
      targetId: BuildTargetIdentifier
  ): Option[TargetDetail] = {
    val commonInfo =
      buildTargets.info(targetId).map(extractCommonTargetDetail)
    val scalaInfo =
      buildTargets.scalaTarget(targetId).map(extractScalaTargetDetail)
    val javaInfo =
      buildTargets.javaTarget(targetId).map(extractJavaTargetDetail)
    commonInfo.map(f => TargetDetail(f, javaInfo, scalaInfo))
  }
  private def extractDependencies(target: BuildTarget) = {
    target.getDependencies.asScala
      .map(f =>
        buildTargets
          .info(f)
          .map(_.getDisplayName())
          .getOrElse("Unknown target")
      )
  }
  private def extractDependentTargets(target: BuildTarget) = {
    buildTargets.all
      .filter(dependentTarget =>
        dependentTarget.getDependencies.contains(target.getId())
      )
      .map(_.getDisplayName())
      .toList
  }
  private def extractCapabilities(
      target: BuildTarget
  ): List[(String, Boolean)] = {
    var capabilities = List.empty[(String, Boolean)]
    capabilities ::= ("Debug", target.getCapabilities().getCanDebug)
    capabilities ::= ("Test", target.getCapabilities().getCanTest)
    capabilities ::= ("Run", target.getCapabilities().getCanRun)
    capabilities ::= ("Compile", target.getCapabilities().getCanCompile)
    capabilities
  }
  private def extractCommonTargetDetail(target: BuildTarget) = {
    val dependencies = extractDependencies(target).sorted
    val dependentTargets = extractDependentTargets(target).sorted
    val capabilities = extractCapabilities(target)
    val sources = buildTargets.sourceItemsToBuildTargets
      .filter(_._2.iterator.asScala.contains(target.getId()))
      .map(_._1)
      .toList
      .sortBy(_.toString)
    CommonTargetDetail(
      target.getId(),
      target.getDisplayName(),
      target.baseDirectory,
      target.getTags.asScala.sorted,
      target.getLanguageIds.asScala.sorted,
      dependencies,
      dependentTargets,
      capabilities,
      sources
    )
  }
  private def extractJavaTargetDetail(target: JavaTarget) = {
    JavaTargetDetail(
      target.classDirectory,
      target.fullClasspath,
      target.options
    )
  }
  private def extractScalaTargetDetail(target: ScalaTarget) = {
    ScalaTargetDetail(
      target.classDirectory,
      target.fullClasspath,
      target.options,
      target.scalaVersion,
      target.scalaBinaryVersion,
      target.scalaPlatform.toString(),
      target.jvmVersion,
      target.jvmHome
    )
  }
}
