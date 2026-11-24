package metals

import sbt.*

private[metals] object SemanticdbPaths {

  /*
   * sbt 1 does not resolve virtual paths, such as `${OUT}/...`, in
   * compiler options, so the options keep absolute paths.
   */
  def optionPath: Def.Initialize[File => String] = Def.setting { (path: File) =>
    path.toString
  }
}
