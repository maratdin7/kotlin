// WITH_RUNTIME

fun <K, T> foo(x: (K) -> T): Pair<K, T> = TODO()

class Foo<K>

fun main() {
    val x = foo<Int, Foo<_>> { it.toFloat() }

}