package com.apk.claw.android.server.routes

import fi.iki.elonen.NanoHTTPD

interface RouteHandler {
    fun canHandle(uri: String, method: NanoHTTPD.Method): Boolean
    fun handle(session: NanoHTTPD.IHTTPSession, ctx: RouteContext): NanoHTTPD.Response
}
