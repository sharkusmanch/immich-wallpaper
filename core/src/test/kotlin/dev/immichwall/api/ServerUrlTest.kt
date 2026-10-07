package dev.immichwall.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ServerUrlTest {
    @Test fun `bare host becomes https`() = assertEquals("https://immich.example.com", ServerUrl.normalize(" immich.example.com "))
    @Test fun `host with port and path keeps them`() = assertEquals("https://host:2283/base", ServerUrl.normalize("host:2283/base/"))
    @Test fun `https is kept and trailing slash dropped`() = assertEquals("https://immich.example.com", ServerUrl.normalize("https://immich.example.com/"))
    @Test fun `http is rejected`() = assertNull(ServerUrl.normalize("http://192.168.1.10:2283"))
    @Test fun `other schemes are rejected`() = assertNull(ServerUrl.normalize("ftp://host"))
    @Test fun `garbage is rejected`() = assertNull(ServerUrl.normalize("not a url"))
    @Test fun `blank stays blank`() = assertEquals("", ServerUrl.normalize("   "))
}
