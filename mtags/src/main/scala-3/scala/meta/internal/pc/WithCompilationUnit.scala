package scala.meta.internal.pc
import java.nio.file.Paths

import scala.meta as m

import scala.meta.internal.metals.CompilerOffsetParams
import scala.meta.internal.mtags.MtagsEnrichments.*
import scala.meta.pc.OffsetParams
import scala.meta.pc.VirtualFileParams

import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.interactive.InteractiveDriver
import dotty.tools.dotc.util.SourceFile

class WithCompilationUnit(
    val driver: InteractiveDriver,
    params: VirtualFileParams,
):
  val uri = params.uri()
  val filePath = Paths.get(uri)
  val sourceText = params.text
  val text = sourceText.toCharArray()
  val source =
    SourceFile.virtual(filePath.toString, sourceText)
  driver.run(uri, source)
  given ctx: Context = driver.currentCtx

  val unit = driver.latestRun
  val compilatonUnitContext = ctx.fresh.setCompilationUnit(unit)
  val offset = params match
    case op: OffsetParams => op.offset()
    case _ => 0
  val offsetParams =
    params match
      case op: OffsetParams => op
      case _ =>
        CompilerOffsetParams(params.uri(), params.text(), 0, params.token())
  val pos = driver.sourcePosition(offsetParams)
end WithCompilationUnit
