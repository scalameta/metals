/*example.AndOrType$package.*/package example

trait Cancelable/*example.Cancelable#*/ 
trait Movable/*example.Movable#*/ 

type Y/*example.AndOrType$package.Y#*/ = (Cancelable & Movable)

type X/*example.AndOrType$package.X#*/ = String | Int
