package metals

import sbt.*
import sbt.Keys.*

private[metals] object SemanticdbPaths {

  /*
   * sbt 2.1 resolves virtual paths, such as `${OUT}/...`, in compiler
   * options. A virtual path keeps the compile cache key the same in every
   * checkout. The pre-releases of sbt 2.1.0 before RC3 do not resolve them
   * in `javacOptions` (sbt/sbt#10029), so they keep absolute paths.
   * `toVirtualFileRef` does not read the file system, but `toVirtualFile`
   * lists a directory to hash its content.
   */
  def optionPath: Def.Initialize[File => String] = Def.setting {
    val converter = fileConverter.value
    val version = sbtVersion.value
    val virtual = !version.startsWith("2.1.0-") || version == "2.1.0-RC3"
    (path: File) =>
      if (virtual) converter.toVirtualFileRef(path.toPath).id
      else path.toString
  }
}
