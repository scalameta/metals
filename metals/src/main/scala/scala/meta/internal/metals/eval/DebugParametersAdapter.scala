package scala.meta.internal.metals.eval

import ch.epfl.scala.{bsp4j => b}
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.meta.internal.eval.JvmDebugProtocol
import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.Compilations
import scala.meta.internal.metals.MetalsEnrichments._

final class DebugParametersAdapter(
    compilations: Compilations,
    buildTargets: BuildTargets
)(implicit ec: ExecutionContext) {
  def adapt(
      params: MetalsDebugProtocol.LaunchParameters
  ): Future[JvmDebugProtocol.LaunchParameters] = {
    val buildTarget = new b.BuildTargetIdentifier(params.buildTarget)
    for {
      result <- compilations.compile(buildTarget)
      _ <- Future(verify(result))
      params <- Future(adapt(params, buildTarget))
    } yield params
  }

  private def verify(result: b.CompileResult): Unit =
    if (result.isError) throw new IllegalStateException("Compilation failed")

  private def adapt(
      params: MetalsDebugProtocol.LaunchParameters,
      buildTarget: b.BuildTargetIdentifier
  ): JvmDebugProtocol.LaunchParameters = {
    val classpath = classpathOf(buildTarget)
    if (classpath.isEmpty)
      throw new IllegalStateException(s"Empty classpath for $buildTarget")

    JvmDebugProtocol.LaunchParameters(
      params.cwd,
      params.mainClass,
      classpath
    )
  }

  private def classpathOf(buildTarget: b.BuildTargetIdentifier): Array[String] =
    for {
      dependency <- buildTargets.scalacOptions(buildTarget).toArray
      classpath <- dependency.getClasspath.asScala
    } yield classpath
}
