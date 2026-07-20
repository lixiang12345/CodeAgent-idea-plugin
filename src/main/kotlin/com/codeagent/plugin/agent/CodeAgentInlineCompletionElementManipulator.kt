package com.codeagent.plugin.agent

import com.intellij.codeInsight.inline.completion.elements.InlineCompletionElement
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionElementManipulator
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionTextElement
import com.intellij.openapi.editor.markup.TextAttributes

class CodeAgentInlineCompletionElementManipulator : InlineCompletionElementManipulator {
    override fun isApplicable(element: InlineCompletionElement): Boolean = element is InlineCompletionTextElement

    override fun truncateFirstSymbol(element: InlineCompletionElement): InlineCompletionElement =
        textElement(element.text.drop(1))

    override fun substring(element: InlineCompletionElement, startOffset: Int, endOffset: Int): InlineCompletionElement =
        textElement(element.text.substring(startOffset.coerceAtLeast(0), endOffset.coerceAtMost(element.text.length)))

    private fun textElement(text: String): InlineCompletionTextElement = InlineCompletionTextElement(text, TextAttributes())
}
