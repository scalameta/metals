package langserver.messages



import org.scalatest.FunSuite
import langserver.types.Position

class CommandProtocolSuite extends FunSuite {
  test("ServerCommand instantiastes") {
    ServerCommand // the constructor may throw
  }

  test("ResultResponse instantiastes") {
    ResultResponse // the constructor may throw
  }

  test("ClientCommand instantiates") {
    ClientCommand // the constructor may throw
  }

  test("Notification instantiates") {
    Notification // the constructor may throw
  }
}