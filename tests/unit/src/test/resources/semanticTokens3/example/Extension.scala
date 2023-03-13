<<package>>/*keyword*/ <<example>>/*namespace*/

<<extension>>/*keyword*/ (<<i>>/*parameter,declaration*/: <<Int>>/*class,abstract*/) <<def>>/*keyword*/ <<asString>>/*method,definition*/: <<String>>/*type*/ = <<i>>/*parameter*/.<<toString>>/*method*/

<<extension>>/*keyword*/ (<<s>>/*parameter,declaration*/: <<String>>/*type*/)
  <<def>>/*keyword*/ <<asInt>>/*method,definition*/: <<Int>>/*class,abstract*/ = <<s>>/*parameter*/.<<toInt>>/*method*/
  <<def>>/*keyword*/ <<double>>/*method,definition*/: <<String>>/*type*/ = <<s>>/*parameter*/ <<*>>/*method*/ <<2>>/*number*/

<<trait>>/*keyword*/ <<AbstractExtension>>/*interface,abstract*/:
  <<extension>>/*keyword*/ (<<d>>/*parameter,declaration*/: <<Double>>/*class,abstract*/)
    <<def>>/*keyword*/ <<abc>>/*method,declaration*/: <<String>>/*type*/