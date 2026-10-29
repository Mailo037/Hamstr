package app.hamstr

actual object Log {
    // Debug lines only with -Dhamstr.debug=true, the way logcat's filters keep them out of the way.
    private val debug = System.getProperty("hamstr.debug") == "true"

    actual fun d(tag: String, message: String, error: Throwable?) { if (debug) print("D", tag, message, error) }
    actual fun i(tag: String, message: String, error: Throwable?) { print("I", tag, message, error) }
    actual fun w(tag: String, message: String, error: Throwable?) { print("W", tag, message, error) }
    actual fun e(tag: String, message: String, error: Throwable?) { print("E", tag, message, error) }

    private fun print(level: String, tag: String, message: String, error: Throwable?) {
        System.err.println("$level/$tag: $message")
        error?.printStackTrace()
    }
}
