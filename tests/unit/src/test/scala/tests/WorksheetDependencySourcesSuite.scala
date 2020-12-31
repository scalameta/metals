package tests

import scala.meta.internal.metals.WorksheetDependencySources

class WorksheetDependencySourcesSuite extends BaseTablesSuite {
  def dependencySources: WorksheetDependencySources = tables.worksheetSources
  test("basic") {
    val textDocument = workspace.resolve("a.scala")
    val worksheet1 = workspace.resolve("w1.worksheet.sc")
    val worksheet2 = workspace.resolve("w2.worksheet.sc")
    assertDiffEqual(
      dependencySources.setWorksheet(textDocument, worksheet1),
      1
    )
    assertDiffEqual(
      dependencySources.getWorksheet(textDocument).get,
      worksheet1
    )
    assertDiffEqual(
      dependencySources.setWorksheet(textDocument, worksheet2),
      1
    )
    assertDiffEqual(
      dependencySources.getWorksheet(textDocument).get,
      worksheet2
    )
  }
}
