package tests

object FileWatcherSlowSuite extends BaseSlowSuite("file-watcher") {
  testAsync("basic") {
    cleanCompileCache("a")
    cleanCompileCache("b")
    cleanCompileCache("c")
    RecursivelyDelete(workspace.resolve("a"))
    for {
      _ <- server.initialize(
        """
          |/project/build.properties
          |sbt.version=1.2.3
          |/build.sbt
          |inThisBuild(List(
          |  scalaVersion := "2.12.7"
          |))
          |lazy val a = project
          |lazy val b = project
          |lazy val c = project.dependsOn(a)
          |/a/src/main/scala/Main.scala
          |package a
          |object A
          |/b/src/main/scala/Main.scala
          |package b
          |object B
          |/c/src/main/scala/Main.scala
          |package c
          |object C {
          |  println(a.ScalaFileEvent)
          |  println(new a.JavaFileEvent)
          |}
        """.stripMargin
      )
      _ = FileWrites.write(
        workspace.resolve("a/src/main/scala/Main.scala"),
        s"""
           |package a
           |object A 
           |object ScalaFileEvent
           |""".stripMargin
      )
      _ = FileWrites.write(
        workspace.resolve("a/src/main/scala/a/JavaFileEvent.java"),
        s"""
           |package a;
           |public class JavaFileEvent {}
           |""".stripMargin
      )
      _ <- server.didOpen("b/src/main/scala/Main.scala")
      _ <- server.didOpen("c/src/main/scala/Main.scala")
      _ = assertNoDiff(client.workspaceDiagnostics, "")
      _ = assertNoDiff(
        server.workspaceDefinitions,
        """
          |/b/src/main/scala/Main.scala
          |package b
          |object B/*L1*/
          |/c/src/main/scala/Main.scala
          |package c
          |object C/*L1*/ {
          |  println/*Predef.scala:392*/(a.ScalaFileEvent/*Main.scala:3*/)
          |  println/*Predef.scala:392*/(new a.JavaFileEvent/*JavaFileEvent.java:2*/)
          |}
          |""".stripMargin
      )
    } yield ()
  }
}
