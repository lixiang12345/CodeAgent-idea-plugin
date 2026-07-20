package com.codeagent.plugin.agent

import com.intellij.openapi.components.service
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.EmptyHttpHeaders
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.QueryStringDecoder
import org.jetbrains.ide.HttpRequestHandler
import java.nio.charset.StandardCharsets

class CodeAgentMcpOAuthCallbackHandler : HttpRequestHandler() {
    override fun isSupported(request: FullHttpRequest): Boolean =
        request.method() == HttpMethod.GET && QueryStringDecoder(request.uri()).path() == CALLBACK_PATH

    override fun process(
        urlDecoder: QueryStringDecoder,
        request: FullHttpRequest,
        context: ChannelHandlerContext,
    ): Boolean {
        val parameters = urlDecoder.parameters()
        val handled = service<McpOAuthService>().completeCallback(
            state = parameters["state"]?.firstOrNull(),
            code = parameters["code"]?.firstOrNull(),
            error = parameters["error"]?.firstOrNull(),
        )
        val html = if (handled) {
            "<html><body><h2>CodeAgent MCP authorization received</h2><p>You can return to the IDE.</p></body></html>"
        } else {
            "<html><body><h2>CodeAgent MCP authorization expired</h2><p>Start the connection again from the IDE.</p></body></html>"
        }
        return sendData(
            html.toByteArray(StandardCharsets.UTF_8),
            "text/html; charset=utf-8",
            request,
            context.channel(),
            EmptyHttpHeaders.INSTANCE,
        )
    }

    private companion object {
        const val CALLBACK_PATH = "/codeagent/mcp/oauth/callback"
    }
}
