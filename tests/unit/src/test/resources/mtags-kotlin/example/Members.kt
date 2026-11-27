   package example.members
//         ^^^^^^^ reference example/
//                 ^^^^^^^ reference example/members/

   class Account private constructor(val owner: String) : example.Base(1L) {
//       ^^^^^^^ definition example/members/Account# CLASS(final)
//       ^^^^^^^ definition example/members/Account#`<init>`(). CONSTRUCTOR
//                                       ^^^^^ definition example/members/Account#owner. METHOD(val)
//                                       ^^^^^ definition example/members/Account#getOwner(). METHOD
       var balance: Long = 0
//         ^^^^^^^ definition example/members/Account#balance. METHOD(var)
//         ^^^^^^^ definition example/members/Account#getBalance(). METHOD
//         ^^^^^^^ definition example/members/Account#setBalance(). METHOD
           private set

       val isEmpty: Boolean
//         ^^^^^^^ definition example/members/Account#isEmpty. METHOD(val)
//         ^^^^^^^ definition example/members/Account#isEmpty(). METHOD
           get() = balance == 0L

       val lazyValue by lazy { owner.length }
//         ^^^^^^^^^ definition example/members/Account#lazyValue. METHOD(val)
//         ^^^^^^^^^ definition example/members/Account#getLazyValue(). METHOD

       init {
           require(owner.isNotEmpty())
       }

       constructor(owner: String, initial: Long) : this(owner) {
//     ^^^^^^^^^^^ definition example/members/Account#`<init>`(+1). CONSTRUCTOR
           balance = initial
       }

       fun deposit(amount: Long) {
//         ^^^^^^^ definition example/members/Account#deposit(). METHOD
           fun check(value: Long) = require(value > 0)
           check(amount)
           balance += amount
       }

       fun deposit(amount: Int) = deposit(amount.toLong())
//         ^^^^^^^ definition example/members/Account#deposit(+1). METHOD

       operator fun plus(other: Account): Account = this
//                  ^^^^ definition example/members/Account#plus(). METHOD

       inner class History {
//                 ^^^^^^^ definition example/members/Account#History# CLASS(final)
//                 ^^^^^^^ definition example/members/Account#History#`<init>`(). CONSTRUCTOR
           fun last(): Long = balance
//             ^^^^ definition example/members/Account#History#last(). METHOD
       }

       class Builder {
//           ^^^^^^^ definition example/members/Account#Builder# CLASS(final)
//           ^^^^^^^ definition example/members/Account#Builder#`<init>`(). CONSTRUCTOR
           var owner: String = ""
//             ^^^^^ definition example/members/Account#Builder#owner. METHOD(var)
//             ^^^^^ definition example/members/Account#Builder#getOwner(). METHOD
//             ^^^^^ definition example/members/Account#Builder#setOwner(). METHOD
           fun build(): Account = Account(owner)
//             ^^^^^ definition example/members/Account#Builder#build(). METHOD
       }

       companion object Factory {
//                      ^^^^^^^ definition example/members/Account#Factory# OBJECT(final)
//                      ^^^^^^^ definition example/members/Account#Factory#`<init>`(). CONSTRUCTOR
           const val DEFAULT_OWNER = "nobody"
//                   ^^^^^^^^^^^^^ definition example/members/Account#Factory#DEFAULT_OWNER. METHOD(val)
//                   ^^^^^^^^^^^^^ definition example/members/Account#Factory#getDEFAULT_OWNER(). METHOD
           @JvmStatic fun empty(): Account = Account(DEFAULT_OWNER)
//                        ^^^^^ definition example/members/Account#Factory#empty(). METHOD
       }
   }

   class Plain {
//       ^^^^^ definition example/members/Plain# CLASS(final)
//       ^^^^^ definition example/members/Plain#`<init>`(). CONSTRUCTOR
       companion object {
//               ^^^^^^ definition example/members/Plain#Companion# OBJECT(final)
//               ^^^^^^ definition example/members/Plain#Companion#`<init>`(). CONSTRUCTOR
           fun create(): Plain = Plain()
//             ^^^^^^ definition example/members/Plain#Companion#create(). METHOD
       }
   }

   val listener = object : Runnable {
//     ^^^^^^^^ definition example/members/MembersKt# CLASS(final, static)
//     ^^^^^^^^ definition example/members/listener. METHOD(val)
//     ^^^^^^^^ definition example/members/getListener(). METHOD
//     ^^^^^^^^ definition example/members/MembersKt#listener. METHOD(val, static)
//     ^^^^^^^^ definition example/members/MembersKt#getListener(). METHOD(static)
       override fun run() {}
   }