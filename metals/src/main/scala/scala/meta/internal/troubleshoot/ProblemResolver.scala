package scala.meta.internal.troubleshoot

import scala.collection.mutable.ListBuffer

import scala.meta.internal.bsp.BspSession
import scala.meta.internal.metals.BloopServers
import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.metals.JavaTarget
import scala.meta.internal.metals.Messages
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MtagsResolver
import scala.meta.internal.metals.ScalaTarget
import scala.meta.internal.metals.ScalaVersions
import scala.meta.internal.semver.SemVer
import scala.meta.io.AbsolutePath

class ProblemResolver(
    workspace: AbsolutePath,
    mtagsResolver: MtagsResolver,
    currentBuildServer: () => Option[BspSession]
) {

  def isUnsupportedBloopVersion(): Boolean = {
    currentBuildServer() match {
      case Some(bspSession) =>
        bspSession.main.name == BloopServers.name && !SemVer
          .isCompatibleVersion(
            BuildInfo.bloopVersion,
            bspSession.main.version
          )
      case None =>
        false
    }
  }

  def recommendation(java: JavaTarget): String = {
    findProblem(java)
      .map(_.message)
      .getOrElse("")
  }

  def recommendation(scala: ScalaTarget): String = {
    findProblem(scala)
      .map(_.message)
      .getOrElse("")
  }

  def problemMessage(
      scalaTargets: List[ScalaTarget],
      javaTargets: List[JavaTarget]
  ): Option[String] = {

    val unsupportedVersions = ListBuffer[String]()
    val deprecatedVersions = ListBuffer[String]()
    val futureVersions = ListBuffer[String]()
    var misconfiguredProjects = 0
    var unsupportedSbt = false
    var deprecatedSbt = false
    var futureSbt = false

    for {
      target <- scalaTargets
      issue <- findProblem(target)
    } yield {
      issue match {
        case UnsupportedScalaVersion(version) => unsupportedVersions += version
        case DeprecatedScalaVersion(version) => deprecatedVersions += version
        case FutureScalaVersion(version) => futureVersions += version
        case _: SemanticDBDisabled => misconfiguredProjects += 1
        case _: MissingSourceRoot => misconfiguredProjects += 1
        case UnsupportedSbtVersion => unsupportedSbt = true
        case DeprecatedSbtVersion => deprecatedSbt = true
        case FutureSbtVersion => futureSbt = true
      }
    }
    for {
      target <- javaTargets
      issue <- findProblem(target)
    } yield {
      issue match {
        case _: JavaSemanticDBDisabled => misconfiguredProjects += 1
        case _: MissingJavaSourceRoot => misconfiguredProjects += 1
        case _: MissingJavaTargetRoot => misconfiguredProjects += 1
      }
    }

    val unsupportedMessage = if (unsupportedVersions.nonEmpty) {
      Some(Messages.UnsupportedScalaVersion.message(unsupportedVersions.toSet))
    } else {
      None
    }
    val deprecatedMessage = if (deprecatedVersions.nonEmpty) {
      Some(Messages.DeprecatedScalaVersion.message(deprecatedVersions.toSet))
    } else {
      None
    }

    val futureMessage = if (futureVersions.nonEmpty) {
      Some(Messages.FutureScalaVersion.message(futureVersions.toSet))
    } else {
      None
    }

    val deprecatedSbtMessage =
      if (deprecatedSbt) Some(Messages.DeprecatedSbtVersion.message) else None
    val unsupportedSbtMessage =
      if (deprecatedSbt) Some(Messages.UnsupportedSbtVersion.message) else None
    val futureSbtMessage =
      if (deprecatedSbt) Some(Messages.FutureSbtVersion.message) else None

    val semanticdbMessage =
      if (
        misconfiguredProjects == (scalaTargets.size + javaTargets.size) && misconfiguredProjects > 0
      ) {
        Some(Messages.CheckDoctor.allProjectsMisconfigured)
      } else if (misconfiguredProjects == 1) {
        val name = scalaTargets
          .find(t => !t.isSemanticdbEnabled)
          .map(_.displayName)
          .getOrElse(
            javaTargets
              .find(t => !t.isSemanticdbEnabled)
              .map(_.displayName)
              .getOrElse("<none>")
          )
        Some(Messages.CheckDoctor.singleMisconfiguredProject(name))
      } else if (misconfiguredProjects > 0) {
        Some(
          Messages.CheckDoctor.multipleMisconfiguredProjects(
            misconfiguredProjects
          )
        )
      } else {
        None
      }

    val allMessages = List(
      deprecatedMessage,
      unsupportedMessage,
      futureMessage,
      deprecatedSbtMessage,
      unsupportedSbtMessage,
      futureSbtMessage,
      semanticdbMessage
    ).flatten

    def scalaVersionsMessages = List(
      deprecatedMessage,
      unsupportedMessage,
      futureMessage
    ).flatten

    allMessages match {
      case single :: Nil => Some(single)
      case Nil => None
      case messages if messages == scalaVersionsMessages =>
        Some(
          s"Your build definition contains multiple unsupported and deprecated Scala versions."
        )
      case _ =>
        Some(
          s"Multiple problems detected in your build."
        )
    }
  }

  private def findProblem(
      scalaTarget: ScalaTarget
  ): Option[ScalaProblem] = {

    def isSupportedScalaVersion(version: String): Boolean =
      mtagsResolver.isSupportedScalaVersion(version)

    scalaTarget.scalaVersion match {
      case version if !isSupportedScalaVersion(version) && scalaTarget.isSbt =>
        if (ScalaVersions.isFutureVersion(version))
          Some(FutureSbtVersion)
        else
          Some(UnsupportedSbtVersion)
      case version if !isSupportedScalaVersion(version) =>
        if (ScalaVersions.isFutureVersion(version))
          Some(FutureScalaVersion(version))
        else
          Some(UnsupportedScalaVersion(version))
      case version if !scalaTarget.isSemanticdbEnabled =>
        Some(
          SemanticDBDisabled(
            version,
            currentBuildServer().map(_.main.name).getOrElse("<none>"),
            isUnsupportedBloopVersion()
          )
        )
      case _
          if !scalaTarget.isSourcerootDeclared && !ScalaVersions
            .isScala3Version(scalaTarget.scalaVersion) =>
        Some(MissingSourceRoot(workspace.scalaSourcerootOption))
      case version
          if ScalaVersions.isDeprecatedScalaVersion(
            version
          ) && scalaTarget.isSbt =>
        Some(DeprecatedSbtVersion)
      case version if ScalaVersions.isDeprecatedScalaVersion(version) =>
        Some(DeprecatedScalaVersion(version))
      case _ => None
    }
  }

  private def findProblem(
      javaTarget: JavaTarget
  ): Option[JavaProblem] = {
    if (!javaTarget.isSemanticdbEnabled)
      Some(
        JavaSemanticDBDisabled(
          currentBuildServer().map(_.main.name).getOrElse("<none>"),
          isUnsupportedBloopVersion()
        )
      )
    else if (!javaTarget.isSourcerootDeclared)
      Some(MissingJavaSourceRoot(workspace.javaSourcerootOption))
    else if (!javaTarget.isTargetrootDeclared)
      Some(
        MissingJavaTargetRoot(
          "-Xplugin:semanticdb -targetroot:javac-classes-directory"
        )
      )
    else None
  }
}
