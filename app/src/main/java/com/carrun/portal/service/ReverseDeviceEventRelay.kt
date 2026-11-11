package com.carrun.portal.service

import com.carrun.portal.events.EventHub
import com.carrun.portal.events.model.PortalEvent

class ReverseDeviceEventRelay(
    private val senderProvider: () -> ((String) -> Boolean)?,
) {
    private val eventListener: (PortalEvent) -> Unit = event@{ event ->
        val sender = senderProvider() ?: return@event
        sender(event.toReverseNotificationJson())
    }

    fun start() {
        EventHub.subscribe(eventListener)
    }

    fun stop() {
        EventHub.unsubscribe(eventListener)
    }
}
