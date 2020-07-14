/*example(Package):13*/package example

/*example.TryCatch(Class):13*/class TryCatch {
  /*example.TryCatch#try(Struct):12*/try {
    /*example.TryCatch#try.x(Constant):5*/val x = 2
    x + 2
  } catch {
    /*example.TryCatch#try.catch(Struct):8*/case t: Throwable =>
  } finally /*example.TryCatch#try.finally(Struct):12*/{
    /*example.TryCatch#try.finally.text(Constant):10*/val text = ""
    text + ""
  }
}
