/*example(Package):12*/package example

/*example.Ord(Interface):6*/trait Ord[T]:
   /*example.Ord#compare(Method):4*/def compare(x: T, y: T): Int

/*example.intOrd(Class):10*/given intOrd: Ord[Int] with
   /*example.intOrd#compare(Method):10*/def compare(x: Int, y: Int) =
     if x < y then -1 else if x > y then +1 else 0

/*example. (Class):12*/given Ord[String] with
   /*example.` `#compare(Method):12*/def compare(x: String, y: String) =
     x.compare(y)
