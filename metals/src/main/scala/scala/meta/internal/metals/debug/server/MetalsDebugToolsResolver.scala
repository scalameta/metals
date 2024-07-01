package scala.meta.internal.metals.debug.server

import java.net.URLClassLoader

import scala.collection.mutable
import scala.util.Success
import scala.util.Try

import scala.meta.internal.metals.Embedded

import ch.epfl.scala.debugadapter.BuildInfo
import ch.epfl.scala.debugadapter.DebugToolsResolver
import ch.epfl.scala.debugadapter.ScalaVersion
import coursierapi.Dependency

class MetalsDebugToolsResolver extends DebugToolsResolver {
  override def resolveExpressionCompiler(
      scalaVersion: ScalaVersion
  ): Try[ClassLoader] = {
    val module = s"${BuildInfo.expressionCompilerName}_$scalaVersion"
    val dependency =
      Dependency.of(BuildInfo.organization, module, BuildInfo.version)
    getOrTryDownload(
      MetalsDebugToolsResolver.expressionCompilerCache,
      scalaVersion,
      dependency,
    )
  }

  override def resolveDecoder(scalaVersion: ScalaVersion): Try[ClassLoader] = {
    val module = s"${BuildInfo.decoderName}_${scalaVersion.binaryVersion}"
    val dependency =
      Dependency.of(BuildInfo.organization, module, BuildInfo.version)
    getOrTryDownload(
      MetalsDebugToolsResolver.decoderCache,
      scalaVersion,
      dependency,
    )
  }

  private def getOrTryDownload(
      cache: mutable.Map[ScalaVersion, ClassLoader],
      scalaVersion: ScalaVersion,
      dependency: Dependency,
  ): Try[ClassLoader] = {
    if (cache.contains(scalaVersion)) Success(cache(scalaVersion))
    else
      Try {
        val downloaded =
          Embedded.downloadDependency(dependency, Some(scalaVersion.value))
        val classLoader =
          new URLClassLoader(downloaded.map(_.toUri.toURL).toArray, null)
        cache.put(scalaVersion, classLoader)
        classLoader
      }
  }
}

object MetalsDebugToolsResolver {
  private val expressionCompilerCache: mutable.Map[ScalaVersion, ClassLoader] =
    mutable.Map.empty
  private val decoderCache: mutable.Map[ScalaVersion, ClassLoader] =
    mutable.Map.empty
}
