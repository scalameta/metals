package scala.meta.internal.metals

import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

import scala.meta.internal.io.FileIO
import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.mtags.MD5
import scala.meta.internal.mtags.Md5Fingerprints
import scala.meta.io.AbsolutePath

final class MutableMd5Fingerprints extends Md5Fingerprints {
  private case class Fingerprint(text: String, md5: String)
  private val fingerprints =
    new ConcurrentHashMap[AbsolutePath, ConcurrentLinkedQueue[Fingerprint]]()
  def add(path: AbsolutePath, text: String): Unit = {
    val md5 = MD5.compute(text)
    val value = fingerprints.computeIfAbsent(
      path,
      { _ =>
        new ConcurrentLinkedQueue()
      }
    )
    value.add(Fingerprint(text, md5))
  }

  override def lookupText(path: AbsolutePath, md5: String): Option[String] = {
    for {
      prints <- Option(fingerprints.get(path))
      fingerprint <- prints.asScala.find(_.md5 == md5)
    } yield {
      // clear older fingerprints that no longer correspond to the semanticDB hash
      prints.clear()
      prints.add(fingerprint)
      fingerprint.text

    }
  }

  override def loadLastValid(
      path: AbsolutePath,
      soughtMd5: String,
      charset: Charset
  ): Option[String] = {
    val text = FileIO.slurp(path, charset)
    val md5 = MD5.compute(text)
    if (soughtMd5 != md5) {
      lookupText(path, soughtMd5)
    } else {
      Some(text)
    }
  }

  override def toString: String = s"Md5FingerprintProvider($fingerprints)"
}
