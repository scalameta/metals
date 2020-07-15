/*example(Package):14*/package example

/*example.TryCatch(Class):14*/class TryCatch {
  /*example.TryCatch#try(Struct):13*/try {
    /*example.TryCatch#try.x(Constant):5*/val x = 2
    x + 2
  } catch {
    /*example.TryCatch#try.catch(Struct):9*/case t: Throwable =>
      t.printStackTrace()
  } finally /*example.TryCatch#try.finally(Struct):13*/{
    /*example.TryCatch#try.finally.text(Constant):11*/val text = ""
    text + ""
  }
}
