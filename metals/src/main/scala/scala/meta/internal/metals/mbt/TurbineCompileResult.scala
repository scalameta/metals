package scala.meta.internal.metals.mbt

import java.nio.file.Files
import java.util.ArrayList
import java.util.HashMap
import java.util.concurrent.ConcurrentHashMap

import scala.util.control.NonFatal

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.metals.MetalsEnrichments.XtensionAbsolutePathBuffers
import scala.meta.io.AbsolutePath

import com.google.common.collect.ImmutableMap
import com.google.turbine.binder.ClassPath
import com.google.turbine.binder.JimageClassBinder
import com.google.turbine.binder.bytecode.BytecodeBoundClass
import com.google.turbine.binder.env.CompoundEnv
import com.google.turbine.binder.env.Env
import com.google.turbine.binder.sym.ClassSymbol
import com.google.turbine.lower.Lower

case class TurbineCompileResult(
    classpath: ClassPath,
    lowered: Lower.Lowered,
) {

  /**
   * Environment over workspace classfiles produced by Turbine, then the
   * compilation classpath, then the JDK. Used to look up [[BytecodeBoundClass]]
   * for a binary name.
   */
  lazy val boundEnv: Env[ClassSymbol, BytecodeBoundClass] = {
    val loweredEnv = new TurbineCompileResult.LoweredBytesEnv(lowered.bytes())
    val env: Env[ClassSymbol, BytecodeBoundClass] =
      CompoundEnv
        .of(TurbineCompileResult.jimageEnv)
        .append(classpath.env())
        .append(loweredEnv)
    loweredEnv.setParent(env)
    env
  }

  def boundClass(binaryName: String): Option[BytecodeBoundClass] =
    Option(boundEnv.get(new ClassSymbol(binaryName)))

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

object TurbineCompileResult {
  private lazy val jimageEnv: Env[ClassSymbol, BytecodeBoundClass] =
    try JimageClassBinder.bindDefault().env()
    catch {
      case NonFatal(e) =>
        scribe.warn(
          s"turbine: failed to bind jimage classpath: ${e.getMessage}"
        )
        emptyEnv
    }

  private val emptyEnv: Env[ClassSymbol, BytecodeBoundClass] =
    new Env[ClassSymbol, BytecodeBoundClass] {
      override def get(sym: ClassSymbol): BytecodeBoundClass = null
    }

  /**
   * Looks up workspace classes from Turbine's lowered classfile bytes. Parent
   * env (JDK + jars + this env) is set after construction so [[BytecodeBoundClass]]
   * can resolve supertypes.
   */
  private class LoweredBytesEnv(
      bytes: ImmutableMap[String, Array[Byte]]
  ) extends Env[ClassSymbol, BytecodeBoundClass] {
    private var parent: Env[ClassSymbol, BytecodeBoundClass] = emptyEnv
    private val cache =
      new ConcurrentHashMap[ClassSymbol, BytecodeBoundClass]()

    def setParent(env: Env[ClassSymbol, BytecodeBoundClass]): Unit =
      parent = env

    override def get(sym: ClassSymbol): BytecodeBoundClass = {
      val classBytes = bytes.get(sym.binaryName())
      if (classBytes == null) null
      else
        cache.computeIfAbsent(
          sym,
          s =>
            new BytecodeBoundClass(
              s,
              () => classBytes,
              parent,
              /* jarFile = */ null,
            ),
        )
    }
  }
}
