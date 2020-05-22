package scala.meta.internal.pantsbuild

import java.nio.file.Files
import java.nio.file.Path

import scala.meta.io.AbsolutePath

case class PantsTarget(
    name: String,
    id: String,
    dependencies: collection.Seq[String],
    excludes: collection.Set[String],
    platform: Option[String],
    transitiveDependencies: collection.Seq[String],
    compileLibraries: collection.Seq[String],
    runtimeLibraries: collection.Seq[String],
    isPantsTargetRoot: Boolean,
    targetType: TargetType,
    pantsTargetType: PantsTargetType,
    globs: PantsGlobs,
    roots: PantsRoots,
    scalacOptions: List[String],
    javacOptions: List[String],
    extraJvmOptions: List[String]
) {
  def isGeneratedTarget: Boolean = name.startsWith(".pants.d")
  private val prefixedId = id.stripPrefix(".")
  def dependencyName: String =
    if (isGeneratedTarget) prefixedId
    else name

  def isTargetRoot: Boolean =
    isPantsTargetRoot &&
      pantsTargetType.isSupported
  val directoryName: String = BloopPants.makeClassesDirFilename(id)
  def baseDirectory(workspace: Path): Path =
    PantsConfiguration
      .baseDirectory(AbsolutePath(workspace), name)
      .toNIO
  def classesDir(bloopDir: Path): Path =
    Files.createDirectories(bloopDir.resolve(directoryName).resolve("classes"))
}
