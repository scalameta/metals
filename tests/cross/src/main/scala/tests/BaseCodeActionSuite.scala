package tests

import scala.meta.internal.metals.EmptyCancelToken
import scala.meta.pc.CancelToken
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.util.control.NonFatal

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
    val code2 = code.replaceAllLiterally("<<", "").replaceAllLiterally(">>", "")
    val offset = code.indexOf("<<") + target.length()
    val file = tmp.resolve(filename)
    Files.write(file.toNIO, code2.getBytes(StandardCharsets.UTF_8))
    try index.addSourceFile(file, Some(tmp))
    catch {
      case NonFatal(e) =>
        println(s"warn: $e")
    }
    workspace.inputs(filename) = code2
    (code2, target, offset)
  }

}
