package scala.meta.internal.builds
import java.nio.file.Paths
import java.security.MessageDigest

import scala.collection.mutable
import scala.util.control.NonFatal

import scala.meta.given
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.internal.parsing.Trees
import scala.meta.io.AbsolutePath
import scala.meta.tokens.Token

object MillDigest extends Digestable {
  override protected def digestWorkspace(
      workspace: AbsolutePath,
      digest: MessageDigest,
  ): Boolean = {
    def analyzeBuildScript(file: AbsolutePath): Boolean = {
      val imported = findImportedScripts(file)
      imported.forall(script => analyzeBuildScript(script)) && Digest
        .digestFile(file, digest)
    }
    val buildFile = workspace.resolve("build.sc")
    analyzeBuildScript(buildFile)
  }

  private case class ImportLinesAcc(
      var hadImport: Boolean = false,
      var hadFile: Boolean = false,
      var insideBraces: Boolean = false,
      var skip: Boolean = false,
      var allPaths: mutable.ListBuffer[AbsolutePath] = new mutable.ListBuffer,
      var current: String = "",
      var prefix: String = "",
  ) {
    def processingFileImport: Boolean = hadFile && hadImport
  }

  // find all `import $file.path`
  private def findImportedScripts(
      file: AbsolutePath
  ): List[AbsolutePath] = {
    try {
      val input = file.toInput
      val tokens = Trees.defaultTokenizerDialect(input).tokenize.get.tokens
      val acc = ImportLinesAcc()
      tokens.foreach { token =>
        token match {
          // recognize line with import statement
          case _: Token.KwImport =>
            acc.hadImport = true
          // end import either with newline or `,` or `}`
          case _: Token.LF | _: Token.LFLF | _: Token.Comma |
              _: Token.RightBrace if acc.processingFileImport =>
            val newPath = Paths.get(acc.prefix).resolve(acc.current + ".sc")
            val relative = file.toNIO.resolveSibling(newPath)
            acc.allPaths.append(AbsolutePath(relative))
            acc.current = ""
            acc.skip = false
            if (token.isInstanceOf[Token.RightBrace]) {
              acc.insideBraces = false
              acc.prefix = ""
            }
            if (!acc.insideBraces) {
              acc.hadFile = false
            }
            if (
              token.isInstanceOf[Token.LF] || token
                .isInstanceOf[Token.LFLF]
            ) {
              acc.hadImport = false
            }
          // if we are after => and haven't encountered `,`, `}` or newline
          case _ if acc.skip =>
          // recognize $file import
          case ident: Token.Ident if ident.pos.text == "$file" =>
            acc.hadFile = true
          // add another token to path
          case ident: Token.Ident if acc.processingFileImport =>
            val toAdd = if (ident.pos.text == "^") ".." else ident.pos.text
            acc.current += (if (acc.current.nonEmpty) "/" + toAdd else toAdd)
          // we don't care about anything after =>, which is jsut a rename
          case _: Token.RightArrow =>
            acc.skip = true
          // open braces, we need to save the prefix
          case _: Token.LeftBrace if acc.processingFileImport =>
            acc.insideBraces = true
            acc.prefix = acc.current
            acc.current = ""
          // don't care about anything else
          case _ =>
        }

      }
      acc.allPaths.toList
    } catch {
      case NonFatal(_) =>
        Nil
    }
  }
}
