package scala.meta.internal.pantsbuild

import java.nio.file.Path

case class PantsTarget(
    name: String,
    id: String,
    dependencies: collection.Seq[String],
    transitiveDependencies: collection.Seq[String],
    libraries: collection.Seq[String],
    isTargetRoot: Boolean,
    targetType: TargetType,
    pantsTargetType: PantsTargetType
) {
  val directoryName: String = BloopPants.makeReadableFilename(name)
  def classesDir(bloopDir: Path): Path =
    bloopDir.resolve(directoryName).resolve("classes")
}
