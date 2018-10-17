package example

class ReflectiveInvocation {
  new Serializable {
    def message = "message"
    // reflective invocation
  }.message

}
