package scala.meta.internal.metals

import scala.collection.mutable.ListBuffer

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.mtags.URIEncoderDecoder
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.BuildTarget
import ch.epfl.scala.bsp4j.BuildTargetIdentifier

class BuildTargetInfo(buildTargets: BuildTargets) {

  def buildTargetDetails(targetName: String, uri: String): String = {
    buildTargets.all
      .find(_.getName() == targetName)
      .orElse(buildTargets.all.find(_.getId.getUri.toString == uri))
      .orElse(
        buildTargets.all.find(
          _.getDisplayName().bazelEscapedDisplayName == targetName
        )
      )
      .map(target => buildTargetDetail(target.getId()))
      .getOrElse(s"Build target $targetName not found")
  }

  private def buildTargetDetail(
      targetId: BuildTargetIdentifier
  ): String = {

    val commonInfo = buildTargets.info(targetId)
    val javaInfo = buildTargets.javaTarget(targetId)
    val scalaInfo = buildTargets.scalaTarget(targetId)

    val output = ListBuffer[String]()

    commonInfo.foreach(info => {
      output += "Target"
      output += s"  ${info.getName()}"

      if (!info.getTags.isEmpty)
        output ++= getSection("Tags", info.getTags.asScala.toList)

      if (!info.getLanguageIds.isEmpty)
        output ++= getSection("Languages", info.getLanguageIds.asScala.toList)

      val capabilities =
        translateCapability("Debug", info.getCapabilities().getCanDebug) ::
          translateCapability("Run", info.getCapabilities().getCanRun) ::
          translateCapability("Test", info.getCapabilities().getCanTest) ::
          translateCapability(
            "Compile",
            info.getCapabilities().getCanCompile,
          ) :: Nil
      output ++= getSection("Capabilities", capabilities)

      val dependencies = getDependencies(info)
      if (dependencies.nonEmpty)
        output ++= getSection("Dependencies", dependencies)

      val dependentTargets = getDependentTargets(info)
      if (dependentTargets.nonEmpty)
        output ++= getSection("Dependent Targets", dependentTargets)
    })
    javaInfo.foreach(info => {
      output += ""
      output += "Javac Options"
      output += "  compile - https://docs.oracle.com/en/java/javase/17/docs/specs/man/javac.html#options"
      output += "  runtime - https://docs.oracle.com/en/java/javase/17/docs/specs/man/java.html#standard-options-for-java"
      output += "  "
      if (info.options.nonEmpty)
        info.options.foreach(f =>
          output += s"  ${if (f.isEmpty) "[BLANK]" else f}"
        )
      else
        output += "  [BLANK]"
    })
    scalaInfo.foreach(info => {
      output += ""
      output += "Scalac Options"
      if (info.scalaBinaryVersion.startsWith("3"))
        output += "  compile - https://docs.scala-lang.org/scala3/guides/migration/options-new.html"
      else
        output += "  compile - https://docs.scala-lang.org/overviews/compiler-options/index.html#Standard_Settings"
      output += "  "
      if (info.options.nonEmpty)
        info.options.foreach(scalacOption =>
          output += s"  ${if (scalacOption.isEmpty) "[BLANK]" else scalacOption}"
        )
      else
        output += "  [BLANK]"

      output ++= getSection("Scala Version", List(info.scalaVersion))
      output ++= getSection(
        "Scala Binary Version",
        List(info.scalaBinaryVersion),
      )
      output ++= getSection(
        "Scala Platform",
        List(info.scalaPlatform.toString()),
      )
      info.jvmVersion.foreach(jvmVersion =>
        output ++= getSection("JVM Version", List(jvmVersion))
      )
      info.jvmHome.foreach(jvmHome =>
        output ++= getSection("JVM Home", List(jvmHome))
      )
    })
    commonInfo.foreach(info => {
      output ++= getSection(
        "Base Directory",
        List(URIEncoderDecoder.decode(info.baseDirectory)),
      )
      output ++= getSection("Sources", getSources(info))
    })

    val scalaClassesDir = scalaInfo.map(_.classDirectory)
    val javaClassesDir = javaInfo.map(_.classDirectory)
    if (scalaClassesDir == javaClassesDir)
      scalaClassesDir.foreach(classesDir =>
        output ++= getSection("Classes Directory", List(classesDir))
      )
    else {
      javaClassesDir.foreach(classesDir =>
        output ++= getSection("Java Classes Directory", List(classesDir))
      )
      scalaClassesDir.foreach(classesDir =>
        output ++= getSection("Scala Classes Directory", List(classesDir))
      )
    }

    val scalaClassPath =
      scalaInfo.flatMap(_.classpath).getOrElse(Nil).map(_.toAbsolutePath)
    val javaClassPath =
      javaInfo.flatMap(_.classpath).getOrElse(Nil).map(_.toAbsolutePath)
    if (scalaClassPath == javaClassPath)
      if (scalaClassPath.isEmpty)
        List("<unresolved>")
      else
        output ++= getSection(
          "Classpath",
          getClassPath(scalaClassPath, targetId),
        )
    else {
      output ++= getSection(
        "Java Classpath",
        getClassPath(javaClassPath, targetId),
      )
      output ++= getSection(
        "Scala Classpath",
        getClassPath(scalaClassPath, targetId),
      )
    }
    output += ""
    output.mkString(System.lineSeparator())
  }

  private def getSection(
      sectionName: String,
      sectionText: List[String],
  ): List[String] =
    "" :: sectionName :: {
      if (sectionText.isEmpty) List("  NONE")
      else
        sectionText.map(text => s"  ${if (text.isEmpty) "[BLANK]" else text}")
    }

  private def translateCapability(
      capability: String,
      hasCapability: Boolean,
  ): String =
    if (hasCapability) capability else s"$capability <- NOT SUPPORTED"

  private def getSingleClassPathInfo(
      path: AbsolutePath,
      filename: String,
      maxFilenameSize: Int,
      buildTargetId: BuildTargetIdentifier,
  ): String = {
    val padding = " " * (maxFilenameSize - filename.size)
    val status = if (path.toFile.exists) {
      val blankWarning = " " * 9
      if (
        path.isDirectory || buildTargets
          .sourceJarFor(buildTargetId, path)
          .nonEmpty
      )
        blankWarning
      else
        "NO SOURCE"
    } else " MISSING "
    val fullName = if (path.toFile.isFile) s" $path" else ""
    s"  $filename$padding $status$fullName"
  }

  private def getClassPath(
      classPath: List[AbsolutePath],
      buildTargetId: BuildTargetIdentifier,
  ): List[String] = {
    def shortenPath(path: AbsolutePath): String = {
      if (path.toFile.isFile)
        path.filename
      else
        path.toString()
    }
    if (classPath.nonEmpty) {
      val maxFilenameSize =
        classPath.map(shortenPath(_).toString.length()).max + 5
      classPath.map(path =>
        getSingleClassPathInfo(
          path,
          shortenPath(path),
          maxFilenameSize,
          buildTargetId,
        )
      )
    } else
      List("NONE")
  }

  private def getDependencies(target: BuildTarget): List[String] = {
    target.getDependencies.asScala
      .map(f =>
        buildTargets
          .info(f)
          .map(_.getName())
          .getOrElse("Unknown target")
      )
      .toList
  }

  private def getDependentTargets(target: BuildTarget): List[String] = {
    buildTargets.all
      .filter(dependentTarget =>
        dependentTarget.getDependencies.contains(target.getId())
      )
      .map(_.getName())
      .toList
  }

  private def getSources(target: BuildTarget): List[String] = {
    buildTargets.sourceItemsToBuildTargets
      .filter(_._2.iterator.asScala.contains(target.getId()))
      .toList
      .map {
        case (path, _) if buildTargets.checkIfGeneratedDir(path) =>
          s"${path}/* (generated)"
        case (path, _) if path.isDirectory => s"${path}/*"
        case (path, _) if !path.exists => s"${path}/* (empty)"
        case (path, _) => path.toString()
      }
      .sorted
  }
}
