package app.hamstr

/**
 * Which app this is. Shared screens use it for the few things that differ, such as
 * "from your phone" against "from this computer"; anything bigger goes through a platform interface.
 */
expect object Platform {
    val desktop: Boolean
}
