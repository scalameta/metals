package scala.meta.internal.telemetry



case class Environment(java: JavaInfo, system: SystemInfo)
object Environment {
  val java: JavaInfo = JavaInfo(
    System.getProperty("java.version", "unknown"),
    System.getProperty("java.vendor", "unknown")
  )
  val system: SystemInfo = SystemInfo(
    System.getProperty("os.arch", "unknown"), 
    System.getProperty("os.name", "unknown"),
    System.getProperty("os.version", "unknown")
  )

  val instance: Environment = Environment(java, system)
}

case class JavaInfo(version: String, distribution: String)
case class SystemInfo(architecture: String, name: String, version: String)