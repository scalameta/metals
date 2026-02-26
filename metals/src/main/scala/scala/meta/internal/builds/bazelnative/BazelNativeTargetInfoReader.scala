package scala.meta.internal.builds.bazelnative

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

import com.google.gson.JsonParser

object BazelNativeTargetInfoReader {

  def readFromBazelBin(bazelBin: Path): Map[String, BspTargetInfo] = {
    if (!Files.isDirectory(bazelBin)) return Map.empty
    val builder = Map.newBuilder[String, BspTargetInfo]

    try {
      val stream = Files.walk(bazelBin)
      try {
        stream
          .filter(p => p.getFileName.toString.endsWith(".bsp-info.json"))
          .forEach { path =>
            readFile(path).foreach { info =>
              if (info.id.nonEmpty) builder += (info.id -> info)
            }
          }
      } finally stream.close()
    } catch {
      case e: Exception =>
        scribe.warn(
          s"[BazelNative] Failed scanning for aspect outputs: ${e.getMessage}"
        )
    }

    builder.result()
  }

  def readFile(path: Path): Option[BspTargetInfo] =
    try {
      val content =
        new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
      Some(parseJson(content))
    } catch {
      case e: Exception =>
        scribe.warn(
          s"[BazelNative] Failed to parse ${path}: ${e.getMessage}"
        )
        None
    }

  def parseJson(json: String): BspTargetInfo = {
    val obj = JsonParser.parseString(json).getAsJsonObject
    BspTargetInfo.fromJson(obj)
  }
}
