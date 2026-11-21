package harness

/** Minimal test runner: test("name") { assert ... }, then finish() at the end of the file. */
class TestRunner {
    static int passed = 0
    static List<String> failed = []

    static void test(String name, Closure body) {
        try {
            body.call()
            passed++
            println "ok   ${name}"
        } catch (Throwable t) {
            failed << name
            println "FAIL ${name}"
            (t.message ?: t.toString()).readLines().each { println "     ${it}" }
        }
    }

    static void finish() {
        println "${passed} passed, ${failed.size()} failed"
        if (failed) System.exit(1)
    }
}
