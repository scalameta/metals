package tests

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import scala.util.control.NonFatal

import scala.meta.dialects
import scala.meta.internal.metals.EmptyCancelToken
import scala.meta.pc.CancelToken

abstract class BaseCodeActionSuite extends BasePCSuite {

  def cancelToken: CancelToken = EmptyCancelToken

  def params(
      code: String
  ): (String, String, Int) = {
    val filename = "test.scala"
    val targetRegex = "<<(.+)>>".r
    val target = targetRegex.findAllMatchIn(code).toList match {
      case Nil => fail("Missing <<target>>")
      case t :: Nil => t.group(1)
      case _ => fail("Multiple <<targets>> found")
    }
    val code2 = code.replace("<<", "").replace(">>", "")
    val offset = code.indexOf("<<") + target.length()
    val file = tmp.resolve(filename)
    Files.write(file.toNIO, code2.getBytes(StandardCharsets.UTF_8))
    val dialect =
      if (BuildInfoVersions.scalaVersion.startsWith("3")) dialects.Scala3
      else dialects.Scala213
    try index.addSourceFile(file, Some(tmp), dialect)
    catch {
      case NonFatal(e) =>
        println(s"warn: $e")
    }
    workspace.inputs(filename) = (code2, dialect)
    (code2, target, offset)
  }

}
