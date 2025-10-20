package scala.meta.internal.jpc

import javax.lang.model.`type`.TypeMirror

import com.sun.source.util.JavacTask
import com.sun.source.util.TreePath
import com.sun.source.util.Trees

class JavaTypeAnalyzer(task: JavacTask) {
  def typeMirror(path: TreePath): TypeMirror =
    Trees.instance(task).getTypeMirror(path)
}
