package tests

import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ScalaVersionSelector
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.{BuildInfo => V}
import scala.meta.internal.mtags.Semanticdbs
import scala.meta.internal.parsing.DocumentSymbolProvider
import scala.meta.internal.parsing.Trees
import scala.meta.internal.{semanticdb => s}

import tests.MetalsTestEnrichments._

/**
 * Checks the positions of document symbols inside a document
 */
abstract class DocumentSymbolSuite(
    directoryName: String,
    inputProperties: => InputProperties,
    scalaVersion: String
) extends DirectoryExpectSuite(directoryName) {

  override lazy val input: InputProperties = inputProperties

  override def testCases(): List[ExpectTestCase] = {
    input.scalaFiles.map { file =>
      ExpectTestCase(
        file,
        { () =>
          val buffers = Buffers()
          buffers.put(file.file, file.code)
          val buildTargets = new BuildTargets()
          val selector =
            new ScalaVersionSelector(
              () =>
                UserConfiguration(fallbackScalaVersion = Some(scalaVersion)),
              buildTargets
            )
          val documentSymbolProvider = new DocumentSymbolProvider(
            new Trees(
              buildTargets,
              buffers,
              selector
            )
          )

          val documentSymbols = documentSymbolProvider
            .documentSymbols(file.file)
            .left
            .get
            .asScala

          val flatSymbols =
            documentSymbols.toSeq.toSymbolInformation(file.file.toURI.toString)
          val textDocument = s.TextDocument(
            schema = s.Schema.SEMANTICDB4,
            language = s.Language.SCALA,
            text = file.input.text,
            occurrences = flatSymbols.map(_.toSymbolOccurrence)
          )

          Semanticdbs.printTextDocument(textDocument)
        }
      )
    }
  }

}

class DocumentSymbolScala2Suite
    extends DocumentSymbolSuite(
      "documentSymbol",
      InputProperties.scala2(),
      V.scala213
    )

class DocumentSymbolScala3Suite
    extends DocumentSymbolSuite(
      "documentSymbol-scala3",
      InputProperties.scala3(),
      V.scala3
    )
