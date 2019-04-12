package example

import java.util.concurrent.CompletableFuture

object Main extends CompletableFuture[Int] {
  println(Option(1).map(_.toString()))
  import java.nio.file.Files
  import java.nio.file.Paths
  List(1).map(identity _)
  System.out.println(identity(), identity(), identity())
  Files.readAllBytes(Paths.get(""))
}
