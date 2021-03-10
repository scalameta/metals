/*example(Package):8*/package example

/*example.Cancelable(Interface):3*/trait Cancelable 
/*example.Movable(Interface):4*/trait Movable 

/*example.Y(TypeParameter):6*/type Y = (Cancelable & Movable)

/*example.X(TypeParameter):8*/type X = String | Int
