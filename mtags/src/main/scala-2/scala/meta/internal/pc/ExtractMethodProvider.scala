package scala.meta.internal.pc

import scala.meta._
import scala.meta.pc.OffsetParams
import org.eclipse.{lsp4j => l}

import org.eclipse.lsp4j.TextEdit

final class ExtractMethodProvider(
    val compiler: MetalsGlobal,
    params: OffsetParams
) {
  import compiler._
    def extractMethod: List[l.TextEdit] = {
      val unit = addCompilationUnit(
        code = params.text(),
        filename = params.uri().toString(),
        cursor = None
      )

      val typedTree = typedTreeAt(unit.position(params.offset))
      pprint.pprintln(typedTree)
      Nil
    }
}