package scala.meta.internal.pc

import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.Optional

import scala.meta.internal.io.FileIO
import scala.meta.internal.jdk.CollectionConverters.*
import scala.meta.io.AbsolutePath

import com.google.gson.JsonPrimitive
import dotty.tools.dotc.core.tasty.TastyHTMLPrinter
import org.eclipse.{lsp4j => l}

object TastyUtils {
  def parseTasty(tastyFile: URI): l.ExecuteCommandParams = {
    val tasty = new TastyHTMLPrinter(
      AbsolutePath.fromAbsoluteUri(tastyFile).readAllBytes
    ).showContents()

    l.ExecuteCommandParams(
      "metals-show-tasty",
      List[Object](tasty).asJava
    )
  }
}
