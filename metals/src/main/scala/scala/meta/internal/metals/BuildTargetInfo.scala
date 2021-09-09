package scala.meta.internal.metals

import java.io.File
import java.net.URI
import java.net.URLEncoder

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import scala.meta.io.AbsolutePath
import scala.meta.internal.metals.MetalsEnrichments._

class BuildTargetInfo(buildTargets: BuildTargets) {
  def buildTargetDetailsHtml(targetName: String): String = {
    val targetIds = buildTargets.allCommon
      .filter(target => target.displayName == targetName)
      .map(_.id)
      .toSeq
    new HtmlBuilder()
      .element("p")(html => buildTargetDetailsHtml(html, targetIds))
      .render
  }
  private def buildTargetDetailsHtml(
      html: HtmlBuilder,
      targetIds: Seq[BuildTargetIdentifier]
  ): Unit = {
    targetIds
      .flatMap(extractTargetDetail)
      .sortBy(f =>
        (f.common.baseDirectory, f.common.displayName, f.common.dataKind)
      )
      .foreach(buildTargetDetailHtml(html, _))
  }
  private def convertClasspathEntry(
      classpathEntry: String,
      html: HtmlBuilder
  ): Unit = {
    val file = new File(URI.create(classpathEntry))
    if (file.exists && file.isFile) {
      val param = s"""["${classpathEntry}"]"""
      html.link(
        s"command:metals.reveal-uri?${URLEncoder.encode(param)}",
        file.getName()
      )
    } else html.text(classpathEntry)
  }
  private def convertOption(option: String, html: HtmlBuilder): Unit = {
    html.text(if (option.isEmpty) "[BLANK]" else option)
  }
  private def convertTarget(
      buildTargetName: String,
      html: HtmlBuilder
  ): Unit = {
    val param = s"""["$buildTargetName"]"""
    val link = s"command:target-info-display?${URLEncoder.encode(param)}"
    html.link(link, buildTargetName)
  }
  private def buildTargetDetailHtml(
      html: HtmlBuilder,
      detail: TargetDetail
  ): Unit = {
    html
      .element("h2")(_.text(detail.common.displayName))
      .element("p")(_.link("command:doctor-run", "Run Doctor"))
      .element("p")(html => {
        html.element("strong")(_.text("Tags"))
        if (detail.common.tags.isEmpty)
          html.unorderedList(List("None")) { html.text(_) }
        html.unorderedList(detail.common.tags) { html.text(_) }
      })
      .element("p")(html => {
        html.element("strong")(_.text("Languages"))
        if (detail.common.languageIds.isEmpty)
          html.unorderedList(List("None")) { html.text(_) }
        html.unorderedList(detail.common.languageIds) { html.text(_) }
      })
      .element("p")(html => {
        html.element("strong")(_.text("Capabilities"))
        if (detail.common.capabilities.isEmpty)
          html.unorderedList(List("None")) { html.text(_) }
        html.unorderedList(detail.common.capabilities) { html.text(_) }
      })
      .element("p")(html => {
        html.element("strong")(_.text("Dependencies"))
        if (detail.common.dependencies.isEmpty)
          html.unorderedList(List("None")) { html.text(_) }
        html.unorderedList(detail.common.dependencies) { f =>
          convertTarget(f, html)
        }
      })
      .element("p")(html => {
        html.element("strong")(_.text("Dependent Targets"))
        if (detail.common.dependentTargets.isEmpty)
          html.unorderedList(List("None")) { html.text(_) }
        html.unorderedList(detail.common.dependentTargets) { f =>
          convertTarget(f, html)
        }
      })
    detail.java.foreach { j =>
      html.element("p")(html => {
        html.element("strong")(
          _.link(
            "https://docs.oracle.com/en/java/javase/16/docs/specs/man/javac.html#options",
            "Javac Options"
          )
        )
        if (j.options.isEmpty)
          html.unorderedList(List("None")) { html.text(_) }
        html.unorderedList(j.options) { f => convertOption(f, html) }
      })
    }
    detail.scala.foreach { s =>
      html.element("p")(html => {
        html.element("strong")(
          _.link(
            "https://docs.scala-lang.org/overviews/compiler-options/index.html#Standard_Settings",
            "Scalac Options"
          )
        )
        if (s.options.isEmpty)
          html.unorderedList(List("None")) { html.text(_) }
        html.unorderedList(s.options) { f => convertOption(f, html) }
      })
      html.element("p")(html => {
        html.element("strong")(_.text("Scala Version"))
        html.unorderedList(List(s.scalaVersion)) { html.text(_) }
      })
      html.element("p")(html => {
        html.element("strong")(_.text("Scala Binary Version"))
        html.unorderedList(List(s.scalaBinaryVersion)) { html.text(_) }
      })
    }
    html
      .element("p")(html => {
        html.element("strong")(_.text("Build Target base directory"))
        html.unorderedList(List(detail.common.baseDirectory)) { html.text(_) }
      })
      .element("p")(html => {
        html.element("strong")(_.text("Source directories"))
        if (detail.common.sources.isEmpty)
          html.unorderedList(List("None")) { html.text(_) }
        html.unorderedList(detail.common.sources) { f => html.text(f.toString) }
      })
    if (detail.sameJavaScalaClassesDir)
      detail.java.foreach { j =>
        html.element("p")(html => {
          html.element("strong")(_.text("Classes directory"))
          html.unorderedList(List(j.classesDirectory)) { html.text(_) }
        })
      }
    else {
      detail.java.foreach { j =>
        html.element("p")(html => {
          html.element("strong")(_.text("Java Classes directory"))
          html.unorderedList(List(j.classesDirectory)) { html.text(_) }
        })
      }
      detail.scala.foreach { s =>
        html.element("p")(html => {
          html.element("strong")(_.text("Scala Classes directory"))
          html.unorderedList(List(s.classesDirectory)) { html.text(_) }
        })
      }
    }
    if (detail.sameJavaScalaClasspaths)
      detail.java.foreach { j =>
        html.element("p")(html => {
          html.element("strong")(_.text("Classpath"))
          if (j.classPath.isEmpty)
            html.unorderedList(List("None")) { html.text(_) }
          html.unorderedList(j.classPath) { f =>
            convertClasspathEntry(f, html)
          }
        })
      }
    else {
      detail.java.foreach { j =>
        html.element("p")(html => {
          html.element("strong")(_.text("Java Classpath"))
          if (j.classPath.isEmpty)
            html.unorderedList(List("None")) { html.text(_) }
          html.unorderedList(j.classPath) { f =>
            convertClasspathEntry(f, html)
          }
        })
      }
      detail.scala.foreach { s =>
        html.element("p")(html => {
          html.element("strong")(_.text("Scala Classpath"))
          if (s.classPath.isEmpty)
            html.unorderedList(List("None")) { html.text(_) }
          html.unorderedList(s.classPath) { f =>
            convertClasspathEntry(f, html)
          }
        })
      }
    }
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
      tags: List[String],
      languageIds: List[String],
      dataKind: String,
      dependencies: List[String],
      dependentTargets: List[String],
      capabilities: List[String],
      sources: Iterable[AbsolutePath]
  )
  private case class JavaTargetDetail(
      classesDirectory: String,
      classPath: List[String],
      options: List[String]
  )
  private case class ScalaTargetDetail(
      classesDirectory: String,
      classPath: List[String],
      options: List[String],
      scalaVersion: String,
      scalaBinaryVersion: String
  )
  private def extractTargetDetail(
      targetId: BuildTargetIdentifier
  ): List[TargetDetail] = {
    val commonInfo =
      buildTargets.commonTarget(targetId).map(extractCommonTargetDetail)
    val scalaInfo =
      buildTargets.scalaTarget(targetId).map(extractScalaTargetDetail)
    val javaInfo =
      buildTargets.javaTarget(targetId).map(extractJavaTargetDetail)
    commonInfo.map(f => TargetDetail(f, javaInfo, scalaInfo)).toList
  }
  private def extractDependencies(target: CommonTarget) = {
    target.dependencies
      .map(f =>
        buildTargets
          .commonTarget(f)
          .map(_.displayName)
          .getOrElse("Unknown target")
      )
  }
  private def extractDependentTargets(target: CommonTarget) = {
    buildTargets.allCommon
      .filter(dependentTarget =>
        dependentTarget.dependencies.contains(target.id)
      )
      .map(_.displayName)
      .toList
  }
  private def extractCapabilities(target: CommonTarget) = {
    var capabilities = List.empty[String]
    if (target.capabilities.getCanCompile)
      capabilities = "Compile" :: capabilities
    if (target.capabilities.getCanRun())
      capabilities = "Run" :: capabilities
    if (target.capabilities.getCanTest())
      capabilities = "Test" :: capabilities
    capabilities
  }
  private def extractCommonTargetDetail(target: CommonTarget) = {
    val dependencies = extractDependencies(target).sorted
    val dependentTargets = extractDependentTargets(target).sorted
    val capabilities = extractCapabilities(target).sorted
    val sources = buildTargets.sourceItemsToBuildTargets
      .filter(_._2.iterator.asScala.contains(target.id))
      .map(_._1)
      .toList
      .sortBy(_.toString)
    CommonTargetDetail(
      target.id,
      target.displayName,
      target.baseDirectory,
      target.tags.sorted,
      target.languageIds.sorted,
      target.dataKind,
      dependencies,
      dependentTargets,
      capabilities,
      sources
    )
  }
  private def extractJavaTargetDetail(target: JavaTarget) = {
    JavaTargetDetail(
      target.classDirectory,
      target.classpath,
      target.options
    )
  }
  private def extractScalaTargetDetail(target: ScalaTarget) = {
    ScalaTargetDetail(
      target.classDirectory,
      target.classpath,
      target.options,
      target.scalaVersion,
      target.scalaBinaryVersion
    )
  }
}
