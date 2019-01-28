package tests

import java.net.URLClassLoader
import java.nio.charset.StandardCharsets
import scala.meta.inputs.Input
import scala.meta.internal.io.InputStreamIO
import scala.meta.internal.metals.JavaSymbolIndexer
import scala.meta.internal.mtags.Symbol
import scala.meta.io.AbsolutePath
import scala.meta.pc.SymbolIndexer
import scala.meta.pc.SymbolVisitor

class SimpleJavaSymbolIndexer(jars: List[AbsolutePath]) extends SymbolIndexer {
  val classpath = new URLClassLoader(jars.map(_.toURI.toURL).toArray, null)
  override def visit(symbol: String, visitor: SymbolVisitor): Unit = {
    val filename = Symbol(symbol).toplevel.value.stripSuffix("#") + ".java"
    val in = classpath.getResourceAsStream(filename)
    if (in != null) {
      val text = new String(InputStreamIO.readBytes(in), StandardCharsets.UTF_8)
      new JavaSymbolIndexer(Input.VirtualFile(filename, text))
        .visit(symbol, visitor)
    }
  }
}
