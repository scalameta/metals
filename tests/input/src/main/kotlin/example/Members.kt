package example.members

class Account private constructor(val owner: String) : example.Base(1L) {
    var balance: Long = 0
        private set

    val isEmpty: Boolean
        get() = balance == 0L

    val lazyValue by lazy { owner.length }

    init {
        require(owner.isNotEmpty())
    }

    constructor(owner: String, initial: Long) : this(owner) {
        balance = initial
    }

    fun deposit(amount: Long) {
        fun check(value: Long) = require(value > 0)
        check(amount)
        balance += amount
    }

    fun deposit(amount: Int) = deposit(amount.toLong())

    operator fun plus(other: Account): Account = this

    inner class History {
        fun last(): Long = balance
    }

    class Builder {
        var owner: String = ""
        fun build(): Account = Account(owner)
    }

    companion object Factory {
        const val DEFAULT_OWNER = "nobody"
        @JvmStatic fun empty(): Account = Account(DEFAULT_OWNER)
    }
}

class Plain {
    companion object {
        fun create(): Plain = Plain()
    }
}

val listener = object : Runnable {
    override fun run() {}
}
