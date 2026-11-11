package com.carrun.portal.keepalive

class KeepAliveStartupException(
    val reason: String,
    cause: Throwable? = null,
) : RuntimeException(reason, cause)
