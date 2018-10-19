package scala.meta.internal.mtags
import scala.meta.io.AbsolutePath

trait FingerprintProvider {
  def lookup(path: AbsolutePath, md5: String): Option[String]
}
object FingerprintProvider {
  val empty: FingerprintProvider = (_, _) => None
}
