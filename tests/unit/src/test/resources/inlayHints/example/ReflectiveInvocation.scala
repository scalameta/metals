package example

class ReflectiveInvocation {
  new Serializable {
    def message/*: String<<java/lang/String#>>*/ = "message"
    // reflective invocation
  }.message/*: String<<java/lang/String#>>*/

}