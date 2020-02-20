package scala.meta.internal.pantsbuild

import java.nio.file.Path
import java.nio.file.Files
import scala.meta.io.AbsolutePath

case class PantsTarget(
    name: String,
    id: String,
    dependencies: collection.Seq[String],
    excludes: collection.Set[String],
    transitiveDependencies: collection.Seq[String],
    libraries: collection.Seq[String],
    isPantsTargetRoot: Boolean,
    targetType: TargetType,
    pantsTargetType: PantsTargetType,
    globs: PantsGlobs,
    roots: PantsRoots
) {

  def isTargetRoot: Boolean =
    isPantsTargetRoot &&
      pantsTargetType.isSupported
  def isGeneratedTarget: Boolean = name.startsWith(".pants.d")
  val directoryName: String = BloopPants.makeClassesDirFilename(name)
  def baseDirectory(workspace: Path): Path =
    PantsConfiguration
      .baseDirectory(AbsolutePath(workspace), name)
      .toNIO
  def classesDir(bloopDir: Path): Path =
    Files.createDirectories(bloopDir.resolve(directoryName).resolve("classes"))
}
