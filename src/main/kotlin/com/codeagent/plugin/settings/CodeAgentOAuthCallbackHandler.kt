package com.codeagent.plugin.settings

import com.intellij.openapi.components.service
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.EmptyHttpHeaders
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.QueryStringDecoder
import org.jetbrains.ide.HttpRequestHandler
import java.nio.charset.StandardCharsets

class CodeAgentOAuthCallbackHandler : HttpRequestHandler() {
    override fun isSupported(request: FullHttpRequest): Boolean =
        request.method() == HttpMethod.GET && QueryStringDecoder(request.uri()).path() == OidcLoginService.CALLBACK_PATH

    override fun process(
        urlDecoder: QueryStringDecoder,
        request: FullHttpRequest,
        context: ChannelHandlerContext,
    ): Boolean {
        val parameters = urlDecoder.parameters()
        val handled = service<OidcLoginService>().completeCallback(
            state = parameters["state"]?.firstOrNull(),
            code = parameters["code"]?.firstOrNull(),
            error = parameters["error"]?.firstOrNull(),
        )
        val html = if (handled) {
            "<html><body><h2>CodeAgent sign-in received</h2><p>You can return to the IDE.</p></body></html>"
        } else {
            "<html><body><h2>CodeAgent sign-in expired</h2><p>Start sign-in again from the IDE.</p></body></html>"
        }
        return sendData(
            html.toByteArray(StandardCharsets.UTF_8),
            "text/html; charset=utf-8",
            request,
            context.channel(),
            EmptyHttpHeaders.INSTANCE,
        )
    }
}
