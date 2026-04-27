package scala.meta.internal.builds.bazelnative

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

import scala.jdk.CollectionConverters._

/**
 * Data model representing the information collected by the Bazel aspect.
 * Field names match the JSON keys emitted by the Starlark aspect's
 * `json.encode()`.
 */

private[bazelnative] object JsonHelpers {

  implicit class RichJsonObject(val obj: JsonObject) extends AnyVal {

    def str(key: String): String =
      if (obj.has(key) && !obj.get(key).isJsonNull)
        obj.get(key).getAsString
      else ""

    def bool(key: String): Boolean =
      if (obj.has(key) && !obj.get(key).isJsonNull)
        obj.get(key).getAsBoolean
      else false

    def int(key: String): Int =
      if (obj.has(key) && !obj.get(key).isJsonNull)
        obj.get(key).getAsInt
      else 0

    def optObj(key: String): Option[JsonObject] =
      if (obj.has(key) && obj.get(key).isJsonObject)
        Some(obj.getAsJsonObject(key))
      else None

    def strList(key: String): List[String] =
      arrElems(key).collect {
        case e if e.isJsonPrimitive => e.getAsString
      }

    def objList(key: String): List[JsonObject] =
      arrElems(key).collect {
        case e if e.isJsonObject => e.getAsJsonObject
      }

    def fileLocList(key: String): List[BspFileLocation] =
      objList(key).map(BspFileLocation.fromJson(_))

    def strMap(key: String): Map[String, String] =
      if (obj.has(key) && obj.get(key).isJsonObject) {
        val m = obj.getAsJsonObject(key)
        m.entrySet()
          .asScala
          .map { e =>
            e.getKey -> e.getValue.getAsString
          }
          .toMap
      } else Map.empty

    private def arrElems(key: String): List[JsonElement] =
      if (obj.has(key) && obj.get(key).isJsonArray) {
        val arr: JsonArray = obj.getAsJsonArray(key)
        arr.iterator().asScala.toList
      } else Nil
  }
}

import JsonHelpers._

case class BspFileLocation(
    path: String = "",
    shortPath: String = "",
    isSource: Boolean = false,
)

object BspFileLocation {
  def fromJson(obj: JsonObject): BspFileLocation =
    BspFileLocation(
      path = obj.str("path"),
      shortPath = obj.str("short_path"),
      isSource = obj.bool("is_source"),
    )
}

case class BspDependency(
    id: String = "",
    dependencyType: Int = 0,
) {
  def isCompile: Boolean = dependencyType == BspDependency.COMPILE
  def isRuntime: Boolean = dependencyType == BspDependency.RUNTIME
}

object BspDependency {
  val COMPILE = 0
  val RUNTIME = 1

  def fromJson(obj: JsonObject): BspDependency =
    BspDependency(
      id = obj.str("id"),
      dependencyType = obj.int("dependency_type"),
    )
}

case class BspJvmOutputs(
    binaryJars: List[BspFileLocation] = Nil,
    interfaceJars: List[BspFileLocation] = Nil,
    sourceJars: List[BspFileLocation] = Nil,
)

object BspJvmOutputs {
  def fromJson(obj: JsonObject): BspJvmOutputs =
    BspJvmOutputs(
      binaryJars = obj.fileLocList("binary_jars"),
      interfaceJars = obj.fileLocList("interface_jars"),
      sourceJars = obj.fileLocList("source_jars"),
    )
}

case class BspJvmTargetInfo(
    jars: List[BspJvmOutputs] = Nil,
    generatedJars: List[BspJvmOutputs] = Nil,
    javacOpts: List[String] = Nil,
    jvmFlags: List[String] = Nil,
    mainClass: String = "",
    args: List[String] = Nil,
    jdeps: List[BspFileLocation] = Nil,
    transitiveCompileTimeJars: List[BspFileLocation] = Nil,
)

object BspJvmTargetInfo {
  def fromJson(obj: JsonObject): BspJvmTargetInfo =
    BspJvmTargetInfo(
      jars = obj.objList("jars").map(BspJvmOutputs.fromJson(_)),
      generatedJars =
        obj.objList("generated_jars").map(BspJvmOutputs.fromJson(_)),
      javacOpts = obj.strList("javac_opts"),
      jvmFlags = obj.strList("jvm_flags"),
      mainClass = obj.str("main_class"),
      args = obj.strList("args"),
      jdeps = obj.fileLocList("jdeps"),
      transitiveCompileTimeJars =
        obj.fileLocList("transitive_compile_time_jars"),
    )
}

case class BspJavaToolchainInfo(
    sourceVersion: String = "",
    targetVersion: String = "",
    javaHome: Option[BspFileLocation] = None,
)

object BspJavaToolchainInfo {
  def fromJson(obj: JsonObject): BspJavaToolchainInfo =
    BspJavaToolchainInfo(
      sourceVersion = obj.str("source_version"),
      targetVersion = obj.str("target_version"),
      javaHome = obj.optObj("java_home").map(BspFileLocation.fromJson(_)),
    )
}

case class BspJavaRuntimeInfo(
    javaHome: Option[BspFileLocation] = None
)

object BspJavaRuntimeInfo {
  def fromJson(obj: JsonObject): BspJavaRuntimeInfo =
    BspJavaRuntimeInfo(
      javaHome = obj.optObj("java_home").map(BspFileLocation.fromJson(_))
    )
}

case class BspScalaTargetInfo(
    scalacOpts: List[String] = Nil,
    compilerClasspath: List[BspFileLocation] = Nil,
    scalatestClasspath: List[BspFileLocation] = Nil,
)

object BspScalaTargetInfo {
  def fromJson(obj: JsonObject): BspScalaTargetInfo =
    BspScalaTargetInfo(
      scalacOpts = obj.strList("scalac_opts"),
      compilerClasspath = obj.fileLocList("compiler_classpath"),
      scalatestClasspath = obj.fileLocList("scalatest_classpath"),
    )
}

case class BspTargetInfo(
    id: String = "",
    kind: String = "",
    tags: List[String] = Nil,
    dependencies: List[BspDependency] = Nil,
    sources: List[BspFileLocation] = Nil,
    generatedSources: List[BspFileLocation] = Nil,
    resources: List[BspFileLocation] = Nil,
    env: Map[String, String] = Map.empty,
    envInherit: List[String] = Nil,
    executable: Boolean = false,
    jvmTargetInfo: Option[BspJvmTargetInfo] = None,
    javaToolchainInfo: Option[BspJavaToolchainInfo] = None,
    javaRuntimeInfo: Option[BspJavaRuntimeInfo] = None,
    scalaTargetInfo: Option[BspScalaTargetInfo] = None,
)

object BspTargetInfo {
  def fromJson(obj: JsonObject): BspTargetInfo =
    BspTargetInfo(
      id = obj.str("id"),
      kind = obj.str("kind"),
      tags = obj.strList("tags"),
      dependencies = obj.objList("dependencies").map(BspDependency.fromJson(_)),
      sources = obj.fileLocList("sources"),
      generatedSources = obj.fileLocList("generated_sources"),
      resources = obj.fileLocList("resources"),
      env = obj.strMap("env"),
      envInherit = obj.strList("env_inherit"),
      executable = obj.bool("executable"),
      jvmTargetInfo =
        obj.optObj("jvm_target_info").map(BspJvmTargetInfo.fromJson(_)),
      javaToolchainInfo = obj
        .optObj("java_toolchain_info")
        .map(BspJavaToolchainInfo.fromJson(_)),
      javaRuntimeInfo =
        obj.optObj("java_runtime_info").map(BspJavaRuntimeInfo.fromJson(_)),
      scalaTargetInfo =
        obj.optObj("scala_target_info").map(BspScalaTargetInfo.fromJson(_)),
    )
}
