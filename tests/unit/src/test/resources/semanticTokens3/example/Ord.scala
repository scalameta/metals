<<package>>/*keyword*/ <<example>>/*namespace*/

<<trait>>/*keyword*/ <<Ord>>/*interface,abstract*/[<<T>>/*typeParameter,abstract*/]:
  <<def>>/*keyword*/ <<compare>>/*method*/(<<x>>/*parameter*/: <<T>>/*typeParameter,abstract*/, <<y>>/*parameter*/: <<T>>/*typeParameter,abstract*/): <<Int>>/*class,abstract*/

<<given>>/*keyword*/ <<intOrd>>/*class*/: <<Ord>>/*interface,abstract*/[<<Int>>/*class,abstract*/] <<with>>/*keyword*/
  <<def>>/*keyword*/ <<compare>>/*method*/(<<x>>/*parameter*/: <<Int>>/*class,abstract*/, <<y>>/*parameter*/: <<Int>>/*class,abstract*/) =
    <<if>>/*keyword*/ <<x>>/*parameter*/ <<<>>/*method*/ <<y>>/*parameter*/ <<then>>/*keyword*/ -<<1>>/*number*/ <<else>>/*keyword*/ <<if>>/*keyword*/ <<x>>/*parameter*/ <<>>>/*method*/ <<y>>/*parameter*/ <<then>>/*keyword*/ +<<1>>/*number*/ <<else>>/*keyword*/ <<0>>/*number*/

<<given>>/*keyword*/ <<Ord>>/*interface,abstract*/[<<String>>/*type*/] <<with>>/*keyword*/
  <<def>>/*keyword*/ <<compare>>/*method*/(<<x>>/*parameter*/: <<String>>/*type*/, <<y>>/*parameter*/: <<String>>/*type*/) =
    <<x>>/*parameter*/.<<compare>>/*method*/(<<y>>/*parameter*/)