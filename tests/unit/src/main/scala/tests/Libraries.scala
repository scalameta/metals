package tests

object Libraries {
  lazy val suite: List[Library] = {
    val buf = List.newBuilder[Library]
    buf += Library.jdk
    buf += Library.scalaLibrary
    buf += Library("org.scalameta", "scalameta_2.12", "3.2.0")
    buf += Library("com.typesafe.akka", "akka-testkit_2.12", "2.5.9")
    buf += Library("com.typesafe.akka", "akka-actor_2.11", "2.5.9")
    buf += Library("org.apache.spark", "spark-sql_2.11", "2.2.1")
    buf += Library("org.apache.kafka", "kafka_2.12", "1.0.0")
    buf += Library("org.apache.flink", "flink-parent", "1.4.1")
    buf += Library("io.grpc", "grpc-all", "1.10.0")
    buf += Library("io.buoyant", "linkerd-core_2.12", "1.4.3")
    buf.result
  }
}
