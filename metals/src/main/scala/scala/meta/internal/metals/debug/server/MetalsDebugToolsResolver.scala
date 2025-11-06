package scala.meta.internal.metals.debug.server

import java.net.URLClassLoader
import java.nio.file.Path

import scala.collection.mutable
import scala.util.Success
import scala.util.Try

import scala.meta.internal.metals.Embedded

import ch.epfl.scala.debugadapter.BuildInfo
import ch.epfl.scala.debugadapter.DebugToolsResolver
import ch.epfl.scala.debugadapter.ScalaVersion
import coursier.Dependency

class MetalsDebugToolsResolver extends DebugToolsResolver {
  def expressionCompilerDependency(scalaVersion: ScalaVersion): Dependency = {
    val module = s"${BuildInfo.expressionCompilerName}_$scalaVersion"
    Embedded.dependencyOf(BuildInfo.organization, module, BuildInfo.version)
  }

  def debugDecoderDependency(scalaVersion: ScalaVersion): Dependency = {
    val module = s"${BuildInfo.decoderName}_${scalaVersion.binaryVersion}"
    Embedded.dependencyOf(BuildInfo.organization, module, BuildInfo.version)
  }

  override def resolveExpressionCompiler(
      scalaVersion: ScalaVersion
  ): Try[ClassLoader] = {
    getOrTryDownload(
      MetalsDebugToolsResolver.expressionCompilerCache,
      scalaVersion,
      expressionCompilerDependency(scalaVersion),
    ) { downloaded =>
      new URLClassLoader(downloaded.map(_.toUri.toURL).toArray, null)
    }
  }

  override def resolveDecoder(scalaVersion: ScalaVersion): Try[Seq[Path]] = {
    getOrTryDownload(
      MetalsDebugToolsResolver.decoderCache,
      scalaVersion,
      debugDecoderDependency(scalaVersion),
    )(identity)
  }

  private def getOrTryDownload[T](
      cache: mutable.Map[ScalaVersion, T],
      scalaVersion: ScalaVersion,
      dependency: Dependency,
  )(f: Seq[Path] => T): Try[T] = {
    if (cache.contains(scalaVersion)) Success(cache(scalaVersion))
    else
      Try {
        val downloaded =
          Embedded.downloadDependency(dependency, Some(scalaVersion.value))
        val value = f(downloaded)
        cache.put(scalaVersion, value)
        value
      }
  }
}

object MetalsDebugToolsResolver {
  private val expressionCompilerCache: mutable.Map[ScalaVersion, ClassLoader] =
    mutable.Map.empty
  private val decoderCache: mutable.Map[ScalaVersion, Seq[Path]] =
    mutable.Map.empty
}
