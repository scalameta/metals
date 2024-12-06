def xd =>>region>>
  val t =
    1 + 2

  t match
    case 3 => println("ok")
    case _ => println("ko")<<region<<
end xd


def xd2 =>>region>>
  val t = 1 + 2

  t match>>region>>
    case 3 =>
      println("ok")
    case _ => println("ko")<<region<<<<region<<
end xd2

def xd3 =>>region>>
  val t =
    1 + 2

  t match>>region>>
    case 3 =>
        println("ok")
    case _ => println("ko")<<region<<<<region<<
end xd3
