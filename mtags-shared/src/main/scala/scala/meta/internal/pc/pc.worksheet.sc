val group = Seq("ArrayBuffer /* comment other */", "Buffer")
val group2 = Seq("ArrayBuffer", "Buffer")

group.indexWhere(_ > "ListBuffer")
group2.indexWhere(_ > "ListBuffer")
"ArrayBuffer /* comment, other */".takeWhile(_ != ' ')
