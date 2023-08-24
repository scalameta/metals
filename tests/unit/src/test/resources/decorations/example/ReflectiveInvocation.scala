package example

class ReflectiveInvocation {
  new Serializable {
    def message/*: String*/ = "message"
    // reflective invocation
  }.message

}