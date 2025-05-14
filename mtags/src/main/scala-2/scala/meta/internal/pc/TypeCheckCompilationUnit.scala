package scala.meta.internal.pc

import scala.tools.nsc.reporters.StoreReporter

import scala.meta.pc.VirtualFileParams

class TypeCheckCompilationUnit(
    val compiler: MetalsGlobal,
    val params: VirtualFileParams
) {
  import compiler._
  val unit: RichCompilationUnit = addCompilationUnit(
    code = params.text(),
    filename = params.uri().toString,
    cursor = None,
    forceNew = true
  )

  private val previousReporter = reporter

  val diagnosticsReporter: StoreReporter = createStoreReporter(settings)
  reporter = diagnosticsReporter
  typeCheck(unit)

  reporter = previousReporter
}
