package scala.meta.internal.metals.mbt

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URI
import javax.tools.JavaFileObject
import javax.tools.SimpleJavaFileObject

import com.google.common.base.Supplier

class TurbineClassfileObject(
    val turbineBinaryName: String,
    val binaryName: String,
    uri: URI,
    bytes: Supplier[Array[Byte]],
) extends SimpleJavaFileObject(uri, JavaFileObject.Kind.CLASS) {
  def this(turbineBinaryName: String, bytes: Supplier[Array[Byte]]) =
    this(
      turbineBinaryName,
      turbineBinaryName.replace('/', '.'),
      new URI(s"turbine://$turbineBinaryName"),
      bytes,
    )
  override def toUri(): URI = uri
  override def openInputStream(): InputStream =
    new ByteArrayInputStream(bytes.get())
}
