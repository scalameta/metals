package scala.meta.internal.metals

import java.util.Optional
import java.util.concurrent.atomic.AtomicBoolean
import java.{util => ju}

import scala.collection.concurrent.TrieMap

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath
import scala.meta.pc.VirtualFileParams
import scala.meta.pc.{OutlineFiles => JOutlineFiles}

import ch.epfl.scala.bsp4j.BuildTargetIdentifier

class OutlineFilesProvider(
    buildTargets: BuildTargets,
    buffers: Buffers,
) {
  private val outlineFiles =
    new TrieMap[BuildTargetIdentifier, BuildTargetOutlineFilesProvider]()

  def shouldRestartPc(
      id: BuildTargetIdentifier,
      reason: PcRestartReason,
  ): Boolean = {
    reason match {
      case DidCompile(true) => true
      case _ =>
        outlineFiles.get(id) match {
          case Some(provider) =>
            // if it was never compiled successfully by the build server
            // we don't restart pc not to lose information from outline compile
            provider.wasSuccessfullyCompiledByBuildServer
          case None => true
        }
    }
  }

  def onDidCompile(id: BuildTargetIdentifier, wasSuccessful: Boolean): Unit = {
    outlineFiles.get(id) match {
      case Some(provider) =>
        if (wasSuccessful) provider.successfulCompilation()
      case None =>
        for {
          scalaTarget <- buildTargets.scalaTarget(id)
          // we don't perform outline compilation for Scala 3
          if (!ScalaVersions.isScala3Version(scalaTarget.scalaVersion))
        } outlineFiles.putIfAbsent(
          id,
          new BuildTargetOutlineFilesProvider(
            buildTargets,
            buffers,
            id,
            wasSuccessful,
          ),
        )
    }
  }

  def didChange(id: String, path: AbsolutePath): Unit =
    buildTargetId(id).foreach(didChange(_, path))

  def didChange(id: BuildTargetIdentifier, path: AbsolutePath): Unit = {
    for {
      provider <- outlineFiles.get(id)
    } provider.didChange(path)
  }

  def getOutlineFiles(id: String): Optional[JOutlineFiles] =
    getOutlineFiles(buildTargetId(id))

  def getOutlineFiles(
      buildTargetId: Option[BuildTargetIdentifier]
  ): Optional[JOutlineFiles] = {
    val res: Option[JOutlineFiles] =
      for {
        id <- buildTargetId
        provider <- outlineFiles.get(id)
        outlineFiles <- provider.outlineFiles()
      } yield outlineFiles
    res.asJava
  }

  def enrichWithOutlineFiles(
      buildTargetId: Option[BuildTargetIdentifier]
  )(vFile: CompilerVirtualFileParams): CompilerVirtualFileParams = {
    val optOutlineFiles =
      for {
        id <- buildTargetId
        provider <- outlineFiles.get(id)
        outlineFiles <- provider.outlineFiles()
      } yield outlineFiles

    optOutlineFiles
      .map(outlineFiles => vFile.copy(outlineFiles = Optional.of(outlineFiles)))
      .getOrElse(vFile)
  }

  def enrichWithOutlineFiles(
      path: AbsolutePath
  )(vFile: CompilerVirtualFileParams): CompilerVirtualFileParams =
    enrichWithOutlineFiles(buildTargets.inferBuildTarget(path))(vFile)

  def clear(): Unit = {
    outlineFiles.clear()
  }

  private def buildTargetId(id: String): Option[BuildTargetIdentifier] =
    Option(id).filter(_.nonEmpty).map(new BuildTargetIdentifier(_))
}

class BuildTargetOutlineFilesProvider(
    buildTargets: BuildTargets,
    buffers: Buffers,
    id: BuildTargetIdentifier,
    wasCompilationSuccessful: Boolean,
) {
  private val changedDocuments =
    ConcurrentHashSet.empty[AbsolutePath]

  private val wasAllOutlined: AtomicBoolean =
    new AtomicBoolean(false)

  private val wasSuccessfullyCompiled: AtomicBoolean =
    new AtomicBoolean(wasCompilationSuccessful)

  def wasSuccessfullyCompiledByBuildServer: Boolean =
    wasSuccessfullyCompiled.get()

  def successfulCompilation(): Unit = {
    wasSuccessfullyCompiled.set(true)
    changedDocuments.clear()
  }

  def didChange(path: AbsolutePath): Boolean =
    changedDocuments.add(path)

  def outlineFiles(): Option[OutlineFiles] = {
    if (!wasSuccessfullyCompiled.get() && !wasAllOutlined.getAndSet(true)) {
      // initial outline compilation that is a substitute for build server compilation
      val allFiles =
        buildTargets
          .buildTargetSources(id)
          .flatMap(_.listRecursive.toList)
          .flatMap(toVirtualFileParams(_))
          .toList
          .asJava
      Some(
        OutlineFiles(
          allFiles,
          isFirstCompileSubstitute = true,
        )
      )
    } else {
      changedDocuments.asScala.toList.flatMap(
        toVirtualFileParams
      ) match {
        case Nil => None
        case files =>
          Some(
            OutlineFiles(
              files.asJava,
              isFirstCompileSubstitute = false,
            )
          )
      }
    }
  }

  private def toVirtualFileParams(
      path: AbsolutePath
  ): Option[VirtualFileParams] =
    buffers.get(path).orElse(path.readTextOpt).map { text =>
      CompilerVirtualFileParams(
        path.toURI,
        text,
      )
    }

}

sealed trait PcRestartReason
case object InverseDependency extends PcRestartReason
case class DidCompile(wasSuccess: Boolean) extends PcRestartReason

case class OutlineFiles(
    files: ju.List[VirtualFileParams],
    isFirstCompileSubstitute: Boolean = false,
) extends JOutlineFiles
