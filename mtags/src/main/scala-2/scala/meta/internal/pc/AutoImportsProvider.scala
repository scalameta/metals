package scala.meta.internal.pc

import scala.collection.mutable

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.pc.AutoImportsResult
import scala.meta.pc.OffsetParams

import org.eclipse.lsp4j.TextEdit

final class AutoImportsProvider(
    val compiler: MetalsGlobal,
    name: String,
    params: OffsetParams
) {
  import compiler._

  def autoImports(): List[AutoImportsResult] = {
    val unit = addCompilationUnit(
      code = params.text(),
      filename = params.uri().toString(),
      cursor = Some(params.offset())
    )
    val pos = unit.position(params.offset)
    // make sure the compilation unit is loaded
    typedTreeAt(pos)

    val importPosition = autoImportPosition(pos, params.text())
    val context = doLocateImportContext(pos)
    val isSeen = mutable.Set.empty[String]
    val symbols = List.newBuilder[Symbol]

    def visit(sym: Symbol): Boolean = {
      val id = sym.fullName
      if (!isSeen(id)) {
        isSeen += id
        symbols += sym
        true
      }
      false
    }

    val visitor = new CompilerSearchVisitor(context, visit)

    search.search(name, buildTargetIdentifier, visitor)

    def isExactMatch(sym: Symbol, name: String): Boolean =
      sym.name.dropLocal.decoded == name

    symbols.result().collect {
      case sym if isExactMatch(sym, name) =>
        val pkg = sym.owner.fullName
        val edits = importPosition match {
          case None =>
            // No import position means we can't insert an import without clashing with
            // existing symbols in scope, so we just do nothing
            Nil
          case Some(value) =>
            val (short, edits) = ShortenedNames.synthesize(
              TypeRef(ThisType(sym.owner), sym, Nil),
              pos,
              context,
              value
            )
            val namePos =
              pos
                .withStart(pos.start - name.length())
                .withEnd(pos.end)
                .toLsp
            val nameEdit = new TextEdit(namePos, short)
            nameEdit :: edits
        }
        AutoImportsResultImpl(pkg, edits.asJava)
    }
  }

}
