package scala.meta.internal.pc

import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.io.AbsolutePath

trait WorksheetSemanticdbProvider {

  private val magicImportsRegex =
    """import\s+(\$ivy|\$repo|\$dep|\$scalac)\..*""".r

  def removeMagicImports(code: String, filePath: AbsolutePath): String = {
    if (filePath.isWorksheet) {
      code.linesIterator
        .map {
          case magicImportsRegex(_) => ""
          case other => other
        }
        .mkString("\n")
    } else {
      code
    }
  }
}
