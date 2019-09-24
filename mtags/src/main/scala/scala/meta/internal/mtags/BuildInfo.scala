package scala.meta.internal.mtags

import com.google.gson.JsonParser
import com.google.gson.JsonObject
import scala.meta.internal.io.InputStreamIO
import java.nio.charset.StandardCharsets
import scala.meta.io.AbsolutePath
import scala.collection.JavaConverters._
import com.google.gson.JsonElement

abstract class BuildInfo(filename: String) {
  private def readResource(filename: String): JsonObject = {
    val text = new String(
      InputStreamIO.readBytes(
        this.getClass().getResourceAsStream(s"/$filename-buildinfo.json")
      ),
      StandardCharsets.UTF_8
    )
    new JsonParser().parse(text).getAsJsonObject()
  }
  lazy val json = readResource(filename)
  protected def getAbsolutePath(implicit name: sourcecode.Name): AbsolutePath =
    AbsolutePath(getString)
  private def getElement(key: String): JsonElement =
    Option(json.get(key)).getOrElse(throw new NoSuchElementException(key))
  protected def getString(implicit name: sourcecode.Name): String =
    getElement(name.value).getAsString()
  protected def getStringList(implicit name: sourcecode.Name): List[String] = {
    getElement(name.value).getAsJsonArray().asScala.map(_.getAsString()).toList
  }
}

object BuildInfo extends BuildInfo("mtags") {
  def scalaCompilerVersion: String = getString
}
