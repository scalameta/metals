package scala.meta.internal.pc

import dotty.tools.dotc.interactive.InteractiveDriver
import dotty.tools.dotc.reporting.Diagnostic
import dotty.tools.dotc.util.SourceFile
import java.net.URI
import scala.collection.concurrent.TrieMap
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.interactive.SourceTree
import dotty.tools.dotc.core.Periods.RunId

class MetalsDriver(
    override val settings: List[String]
) extends InteractiveDriver(settings):

  private val lastCompiled: TrieMap[URI, (String, RunId)] = TrieMap.empty

  private def md5(text: String): String =
    java.security.MessageDigest
      .getInstance("MD5")
      .digest(text.getBytes)
      .map("%02x".format(_))
      .mkString

  private def alreadyCompiled(uri: URI, hash: String): Boolean =
    lastCompiled
      .get(uri)
      .map(cache => cache._1 == hash && cache._2 != currentCtx.runId)
      .getOrElse(false)

  override def run(uri: URI, source: SourceFile): List[Diagnostic] =
    val content = source.content.mkString
    val contentHash = md5(content)
    if alreadyCompiled(uri, contentHash) then
      println(s"no compile: $uri")
      Nil
    else
      println(s"compile: $uri")
      lastCompiled.put(uri, (contentHash, currentCtx.runId))
      super.run(uri, source)

  override def run(uri: URI, sourceCode: String): List[Diagnostic] =
    val contentHash = md5(sourceCode)
    if alreadyCompiled(uri, contentHash) then
      println(s"no compile: $uri")
      Nil
    else
      println(s"compile: $uri")
      lastCompiled.put(uri, (contentHash, currentCtx.runId))
      super.run(uri, sourceCode)

end MetalsDriver
