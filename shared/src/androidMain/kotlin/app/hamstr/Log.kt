package app.hamstr

actual object Log {
    actual fun d(tag: String, message: String, error: Throwable?) { android.util.Log.d(tag, message, error) }
    actual fun i(tag: String, message: String, error: Throwable?) { android.util.Log.i(tag, message, error) }
    actual fun w(tag: String, message: String, error: Throwable?) { android.util.Log.w(tag, message, error) }
    actual fun e(tag: String, message: String, error: Throwable?) { android.util.Log.e(tag, message, error) }
}
