package scala.meta.internal.metals.mbt

import java.net.URI

import scala.meta.io.AbsolutePath

/**
 * Helper for creating and parsing virtual URIs for proto-generated Java files.
 *
 * URIs have the format: `file:///path/to/file.proto.metals-proto-java/ClassName.java`
 *
 * This format works with SimpleJavaFileObject (has a valid path), while allowing
 * us to intercept definition requests and redirect them to the actual proto file.
 */
object ProtoJavaVirtualFile {

  /** Marker segment in the path that identifies proto-generated Java files */
  val PathMarker = ".metals-proto-java/"

  /**
   * Creates a virtual URI for a proto-generated Java class.
   *
   * @param protoFile The source proto file
   * @param className The generated Java class name (without .java extension)
   * @return A URI like `file:///path/to/file.proto.metals-proto-java/ClassName.java`
   */
  def makeUri(protoFile: AbsolutePath, className: String): URI = {
    // Format: file:///path/to/file.proto.metals-proto-java/ClassName.java
    // This creates a path that:
    // 1. SimpleJavaFileObject accepts (has a valid path)
    // 2. We can detect by looking for .metals-proto-java/
    // 3. We can extract the proto path from
    URI.create(s"${protoFile.toURI}$PathMarker$className.java")
  }

  /**
   * Checks if a URI is a proto-generated Java virtual file.
   */
  def isProtoJavaUri(uri: String): Boolean = {
    uri.contains(PathMarker)
  }

  /**
   * Extracts the proto file path from a proto-java virtual URI.
   *
   * @param uri A URI like `file:///path/to/file.proto.metals-proto-java/ClassName.java`
   * @return The proto file path, or None if the URI is not a valid proto-java URI
   */
  def extractProtoPath(uri: String): Option[AbsolutePath] = {
    if (!isProtoJavaUri(uri)) return None

    val markerIndex = uri.indexOf(PathMarker)
    if (markerIndex < 0) return None

    val protoUri = uri.substring(0, markerIndex)
    try {
      Some(AbsolutePath.fromAbsoluteUri(URI.create(protoUri)))
    } catch {
      case _: Exception => None
    }
  }

  /**
   * Extracts the Java class name from a proto-java virtual URI.
   *
   * @param uri A URI like `file:///path/to/file.proto.metals-proto-java/ClassName.java`
   * @return The class name (e.g., "ClassName"), or None if invalid
   */
  def extractClassName(uri: String): Option[String] = {
    if (!isProtoJavaUri(uri)) return None

    val markerIndex = uri.indexOf(PathMarker)
    if (markerIndex < 0) return None

    val className =
      uri.substring(markerIndex + PathMarker.length).stripSuffix(".java")
    if (className.nonEmpty) Some(className) else None
  }
}
