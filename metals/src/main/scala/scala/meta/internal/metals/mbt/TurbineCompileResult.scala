package scala.meta.internal.metals.mbt

import java.nio.file.Files
import java.util.ArrayList
import java.util.HashMap

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.metals.MetalsEnrichments.XtensionAbsolutePathBuffers
import scala.meta.io.AbsolutePath

import com.google.turbine.binder.ClassPath
import com.google.turbine.binder.sym.ClassSymbol
import com.google.turbine.lower.Lower

case class TurbineCompileResult(
    classpath: ClassPath,
    lowered: Lower.Lowered,
) {
  val symbolsByPackage: collection.Map[String, ArrayList[ClassSymbol]] = {
    val x = new HashMap[String, ArrayList[ClassSymbol]]()
    lowered.symbols().forEach { sym =>
      var buf = x.get(sym.packageName())
      if (buf == null) {
        buf = new ArrayList[ClassSymbol]()
        x.put(sym.packageName(), buf)
      }
      buf.add(sym)
    }
    x.asScala
  }

  def javap(className: String): String = {
    import scala.sys.process._
    val tmp = Files.createTempDirectory("javap")
    try {
      for {
        (pkg, symbols) <- symbolsByPackage
        _ = Files.createDirectories(tmp.resolve(pkg))
        sym <- symbols.asScala
        bytes <- Option(lowered.bytes().get(sym.binaryName()))
      } {
        Files.write(
          tmp.resolve(sym.binaryName() + ".class"),
          bytes,
        )
      }
      val javapOutput = s"javap -cp $tmp $className".!!
      javapOutput
    } finally {
      AbsolutePath(tmp).deleteRecursively()
    }
  }
}
