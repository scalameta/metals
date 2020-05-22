package scala.meta.internal.pantsbuild

import java.nio.file.Path
import java.nio.file.Paths

import ujson.Obj

final case class PantsPreferredJvmDistribution(
    javaHome: Option[Path]
)

object PantsPreferredJvmDistribution {
  def fromJson(json: Obj): PantsPreferredJvmDistribution = {
    val javaHome = for {
      jvmPlatforms <- json.value.get(PantsKeys.jvmPlatforms)
      defaultPlatform <- jvmPlatforms.obj.get(PantsKeys.defaultPlatform)
      preferredJvmDistribution <- json.value.get(
        PantsKeys.preferredJvmDistributions
      )
      javaHome <- preferredJvmDistribution.obj.get(defaultPlatform.str)
      strict <- javaHome.obj.get(PantsKeys.strict)
    } yield strict.str
    PantsPreferredJvmDistribution(
      javaHome
        .orElse(Option(System.getenv("JAVA_HOME")))
        .map(Paths.get(_))
    )
  }
}
