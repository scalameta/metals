package tests

import scala.collection.mutable.ListBuffer

import scala.meta.Dialect
import scala.meta.dialects.Scala213
import scala.meta.dialects.Scala3
import scala.meta.internal.io.FileIO
import scala.meta.internal.io.PathIO
import scala.meta.internal.mtags.Mtags

/**
 * Assert that Mtags.toplevels method works as expected.
 */
abstract class ToplevelSuite(
    newInput: InputProperties,
    dialect: Dialect,
    filename: String,
) extends SingleFileExpectSuite(filename) {

  override lazy val input: InputProperties = newInput
  override def obtained(): String = {
    val toplevels = ListBuffer.empty[String]
    val missingSymbols = ListBuffer.empty[String]
    def forInput(input: InputProperties, dialect: Dialect) = {
      for {
        dir <- input.sourceDirectories if dir.isDirectory
        ls = FileIO.listAllFilesRecursively(dir)
        // Filter to Scala and Java files only - proto files are tested in ProtobufToplevelSuite
        relpath <- ls.files
        ext = PathIO.extension(relpath.toNIO)
        if ext == "scala" || ext == "java"
        reluri = relpath.toURI(isDirectory = false).toString
        path = dir.resolve(relpath)
        fileSymtab = symtab(path)
        toplevel <- Mtags.testingSingleton.topLevelSymbols(path, dialect)
      } {
        if (fileSymtab.info(toplevel).isEmpty) {
          missingSymbols += toplevel
        }
        toplevels += s"$reluri -> $toplevel"
      }
    }
    forInput(input, dialect)
    if (missingSymbols.nonEmpty) {
      fail(s"missing symbols:\n${missingSymbols.mkString("\n")}")
    }
    toplevels.sorted.mkString("\n")
  }
}

// Skipped because it's failing to find SemanticDB files for Java sources.
@munit.IgnoreSuite
class ToplevelsScala3Suite
    extends ToplevelSuite(
      InputProperties.scala3(),
      Scala3,
      "toplevels-scala3.expect",
    )

class ToplevelsScala2Suite
    extends ToplevelSuite(
      InputProperties.scala2(),
      Scala213,
      "toplevels.expect",
    )
