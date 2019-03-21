package example

package app {
  import example.`type`.Banana
  object Main {
    val `type` = 42
    def foobar(x: Int): String = x.toString()
    // Main.foobar()
  }
}

package `type` {
  object Banana
}
