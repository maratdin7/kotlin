class A {
    val def = Def("Hello")
}

data class Def(<!ACCESS_TO_UNINITIALIZED_VALUE!>val a: String = run { y }<!>,  <!ACCESS_TO_UNINITIALIZED_VALUE!>val y: String = "Tmp"<!>)