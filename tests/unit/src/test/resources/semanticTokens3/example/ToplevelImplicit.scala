<<package>>/*keyword*/ <<example>>/*namespace*/

<<// This wasn't possible in Scala 2>>/*comment*/
<<implicit>>/*modifier*/ <<class>>/*keyword*/ <<Xtension>>/*class*/(<<number>>/*variable,declaration,readonly*/: <<Int>>/*class,abstract*/) {
    <<def>>/*keyword*/ <<increment>>/*method,definition*/: <<Int>>/*class,abstract*/ = <<number>>/*variable,readonly*/ <<+>>/*method*/ <<1>>/*number*/
}

<<implicit>>/*modifier*/ <<class>>/*keyword*/ <<XtensionAnyVal>>/*class*/(<<private>>/*modifier*/ <<val>>/*keyword*/ <<number>>/*variable,declaration,readonly*/: <<Int>>/*class,abstract*/) <<extends>>/*keyword*/ <<AnyVal>>/*class,abstract*/ {
    <<def>>/*keyword*/ <<double>>/*method,definition*/: <<Int>>/*class,abstract*/ = <<number>>/*variable,readonly*/ <<*>>/*method*/ <<2>>/*number*/
}