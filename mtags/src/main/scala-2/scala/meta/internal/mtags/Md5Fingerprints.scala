package scala.meta.internal.mtags
import java.nio.charset.Charset

import scala.meta.io.AbsolutePath

/**
 * Maps MD5 fingerprints to full text contents.
 */
trait Md5Fingerprints {
  def lookupText(path: AbsolutePath, md5: String): Option[String]

  def loadLastValid(
      path: AbsolutePath,
      soughtMd5: String,
      charset: Charset
  ): Option[String]
}

object Md5Fingerprints {
  def empty: Md5Fingerprints =
    new Md5Fingerprints {
      override def lookupText(path: AbsolutePath, md5: String): Option[String] =
        None

      override def loadLastValid(
          path: AbsolutePath,
          soughtMd5: String,
          charset: Charset
      ): Option[String] = None
    }
}
