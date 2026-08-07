package scala.meta.internal.jpc

import com.sun.source.tree.CompilationUnitTree
import com.sun.source.util.Trees

// The result of parsing a Java source: its tree and all of its comments.
private[internal] final case class JavaParseResult(
    trees: Trees,
    unit: CompilationUnitTree,
    comments: List[JavaComment]
)
