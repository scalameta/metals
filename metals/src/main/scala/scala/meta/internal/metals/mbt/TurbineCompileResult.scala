package scala.meta.internal.metals.mbt

import java.util.ArrayList
import java.util.HashMap

import scala.meta.internal.jdk.CollectionConverters._

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
}
