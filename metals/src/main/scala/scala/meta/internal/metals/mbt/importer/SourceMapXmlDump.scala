package scala.meta.internal.metals.mbt.importer

import scala.xml.XML

class SourceMapXmlDump(xmlDump: String) {

  private lazy val root = XML.loadString(xmlDump)

  lazy val locationBySourceFile: Map[String, String] = {
    val entries = for {
      sourceFile <- root \\ "source-file"
    } yield {
      val label = (sourceFile \ "@name").text
      val location = (sourceFile \ "@location").text
      (label, location)
    }
    entries.toMap
  }

}
