package com.aasra.companion.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A tool can request visible permission help, but can never grant permissions. */
object PhoneAccessBus {
    enum class Request { CONTACTS_AND_CALLS, SMS }
    private val mutableRequest = MutableStateFlow<Request?>(null)
    val request = mutableRequest.asStateFlow()
    fun contactsAndCalls() { mutableRequest.value = Request.CONTACTS_AND_CALLS }
    fun sms() { mutableRequest.value = Request.SMS }
    fun clear() { mutableRequest.value = null }
}
