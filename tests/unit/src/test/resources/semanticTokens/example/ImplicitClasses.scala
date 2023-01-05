<<package>>/*keyword*/ <<example>>/*namespace*/

<<object>>/*keyword*/ <<ImplicitClasses>>/*class*/ {
  <<implicit>>/*modifier*/ <<class>>/*keyword*/ <<Xtension>>/*class*/(<<number>>/*variable,readonly*/: <<Int>>/*class,abstract*/) {
    <<def>>/*keyword*/ <<increment>>/*method*/: <<Int>>/*class,abstract*/ = <<number>>/*variable,readonly*/ <<+>>/*method,abstract*/ <<1>>/*number*/
  }
  <<implicit>>/*modifier*/ <<class>>/*keyword*/ <<XtensionAnyVal>>/*class*/(<<private>>/*modifier*/ <<val>>/*keyword*/ <<number>>/*variable,readonly*/: <<Int>>/*class,abstract*/) <<extends>>/*keyword*/ <<AnyVal>>/*class,abstract*/ {
    <<def>>/*keyword*/ <<double>>/*method*/: <<Int>>/*class,abstract*/ = <<number>>/*variable,readonly*/ <<*>>/*method,abstract*/ <<2>>/*number*/
  }
}