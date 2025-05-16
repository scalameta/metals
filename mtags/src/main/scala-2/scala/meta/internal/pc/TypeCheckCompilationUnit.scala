package scala.meta.internal.pc

import scala.meta.pc.{OffsetParams, VirtualFileParams}

class TypeCheckCompilationUnit(
    val compiler: MetalsGlobal,
    val params: VirtualFileParams
) {
  import compiler._
  val unit: RichCompilationUnit = addCompilationUnit(
    code = params.text(),
    filename = params.uri().toString,
    cursor = None
  )
  val offset: Int = params match {
    case p: OffsetParams => p.offset()
    case _: VirtualFileParams => 0
  }
  val pos: Position = unit.position(offset)
  lazy val text = unit.source.content

  private val previousReporter = reporter

  private val console = storeReporterConstructor(settings)
  reporter = console
  typeCheck(unit)

  reporter = previousReporter

  def getInfos = {
    console.infos.toSet
  }
}
