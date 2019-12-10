package scala.meta.internal.pc

import scala.meta.pc.OffsetParams
import scala.meta.pc.AutoImportsResult
import scala.collection.mutable
import scala.collection.JavaConverters._

final class AutoImportsProvider(
    val compiler: MetalsGlobal,
    name: String,
    params: OffsetParams
) {
  import compiler._

  def autoImports(): List[AutoImportsResult] = {
    val unit = addCompilationUnit(
      code = params.text,
      filename = params.filename,
      cursor = Some(params.offset)
    )
    val pos = unit.position(params.offset)
    val importPosition = autoImportPosition(pos, params.text())
    val context = doLocateImportContext(pos, importPosition)
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

    val visitor = new CompilerSearchVisitor(name, context, visit)

    search.search(name, buildTargetIdentifier, visitor)

    symbols.result.collect {
      case sym if sym.name.dropLocal.decoded == name =>
        val ident = Identifier.backtickWrap(sym.name.dropLocal.decoded)
        val pkg = sym.owner.fullName
        val edits = importPosition match {
          case None => Nil
          case Some(value) =>
            val (short, edits) = ShortenedNames.synthesize(
              TypeRef(ThisType(sym.owner), sym, Nil),
              pos,
              context,
              value
            )
            edits
        }
        AutoImportsResultImpl(pkg, edits.asJava)
    }
  }

}
