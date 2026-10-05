// expect: unexpected token: ->
//
// A Java-style lambda is Groovy 3 syntax: groovy:4 compiles it, Groovy 2.4 (the hub's line) cannot
// parse it. Not yet seen on the hub itself. Positive control for tests/groovy/hub_compile.sh.
def doubled = [1, 2, 3].collect(x -> x * 2)
