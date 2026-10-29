package app.hamstr

/** Logcat on the phone, standard error on the desktop. */
expect object Log {
    fun d(tag: String, message: String, error: Throwable? = null)
    fun i(tag: String, message: String, error: Throwable? = null)
    fun w(tag: String, message: String, error: Throwable? = null)
    fun e(tag: String, message: String, error: Throwable? = null)
}
