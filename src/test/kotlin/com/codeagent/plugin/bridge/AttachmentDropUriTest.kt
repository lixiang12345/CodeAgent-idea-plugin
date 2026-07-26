package com.codeagent.plugin.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AttachmentDropUriTest {
    @Test
    fun `accepts absolute local file URIs`() {
        assertEquals("/project/src/main.ts", AttachmentContextResolver.localFilePath("file:///project/src/main.ts").toString())
        assertEquals("/project/a b.md", AttachmentContextResolver.localFilePath("file:///project/a%20b.md").toString())
        assertEquals("/project/src/main.ts", AttachmentContextResolver.localFilePath("  file:///project/src/main.ts  ").toString())
    }

    @Test
    fun `rejects anything that is not a local file`() {
        assertNull(AttachmentContextResolver.localFilePath("https://example.com/main.ts"))
        assertNull(AttachmentContextResolver.localFilePath("javascript:alert(1)"))
        assertNull(AttachmentContextResolver.localFilePath("data:text/plain,hello"))
        // A UNC-style authority would read from a remote host, so it is not a local path.
        assertNull(AttachmentContextResolver.localFilePath("file://remote-host/share/main.ts"))
        assertNull(AttachmentContextResolver.localFilePath("/project/src/main.ts"))
        assertNull(AttachmentContextResolver.localFilePath(""))
        assertNull(AttachmentContextResolver.localFilePath("file:"))
    }
}
