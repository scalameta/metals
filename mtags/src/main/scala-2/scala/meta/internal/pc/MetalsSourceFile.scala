package scala.meta.internal.pc
import scala.reflect.internal.util.BatchSourceFile
import scala.reflect.io.VirtualFile

import scala.meta.pc.VirtualFileParams

/**
 * An implementation of BatchSourceFile that implements equality based on virtual file path name.
 *
 * It is unclear why the default implementation special-cases virtual files. The presentation compiler
 * uses source file equality to prune the `allSources` list of managed files (that are compiled by the
 * background compilation thread).
 */
class MetalsSourceFile(filename: String, contents: Array[Char])
    extends BatchSourceFile(new VirtualFile(filename), contents) {

   def this(params: VirtualFileParams) = {
    this(params.uri().toString, params.text().toCharArray)
  }

  override def equals(that: Any): Boolean = that match {
    case other: MetalsSourceFile => this.file == other.file
    case _ => super.equals(that)
  }

  override def hashCode(): Int = path.hashCode()
}
