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
    val pos = unit.position(params.offset())
    val typedTree = typedTreeAt(pos)
    pprint.pprintln(typedTree)
    val context = doLocateImportContext(pos)
    val re: scala.collection.Map[Symbol, Name] = renamedSymbols(context)
    val history = new ShortenedNames(
      lookupSymbol = name =>
        context.lookupSymbol(name, sym => !sym.isStale) :: Nil,
      config = renameConfig,
      renames = re
    )
    def prettyType(tpe: Type) =
      metalsToLongString(tpe.widen.finalResultType, history)
    
    Nil
  }
}
