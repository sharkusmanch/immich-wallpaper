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

    // Addresses that show one host and reach another: the text before '@' is user info.
    private val disguised = listOf(
        "https://photos.example.test@evil.example",
        "https://photos.example.test\n\n\n@evil.example",
        "https://photos.example.test   @evil.example",
        "https://photos.example.test‮@evil.example",
        "https://evil.example/‮tset.elpmaxe.sotohp",
        "https://evil.example/\nServer URL: https://photos.example.test",
    )

    @Test fun `an address that shows one host and reaches another is rejected`() {
        for (address in disguised) assertNull(ServerUrl.normalize(address), address)
    }

    @Test fun `user info is rejected`() {
        assertNull(ServerUrl.normalize("https://user@photos.example.test"))
        assertNull(ServerUrl.normalize("https://user:secret@photos.example.test"))
        assertNull(ServerUrl.normalize("user@photos.example.test"))
        assertNull(ServerUrl.normalize("https://:secret@photos.example.test"))
    }

    @Test fun `control characters and whitespace inside the address are rejected`() {
        for (c in listOf("\n", "\r", "\t", " ", "\u0000", "\u007F", "\u0085", " ", " ", " ")) {
            assertNull(ServerUrl.normalize("https://photos.example.test/a${c}b"), "U+%04X".format(c[0].code))
        }
    }

    @Test fun `bidirectional and other format characters are rejected`() {
        val format = listOf(0x200E, 0x200F, 0x202A, 0x202B, 0x202C, 0x202D, 0x202E, 0x2066, 0x2067, 0x2068, 0x2069) +
            listOf(0x00AD, 0x200B, 0x200C, 0x200D, 0x2060, 0xFEFF, 0x061C)
        for (code in format) {
            val c = code.toChar()
            assertNull(ServerUrl.normalize("https://photos.example.test/a${c}b"), "path U+%04X".format(code))
            assertNull(ServerUrl.normalize("https://pho${c}tos.example.test"), "host U+%04X".format(code))
        }
    }

    @Test fun `whitespace around the address is still trimmed`() =
        assertEquals("https://photos.example.test", ServerUrl.normalize("\t https://photos.example.test/ \n"))

    @Test fun `ordinary addresses are still accepted as typed`() {
        assertEquals("https://photos.example.test", ServerUrl.normalize("https://photos.example.test"))
        assertEquals("https://photos.example.test:2283", ServerUrl.normalize("https://photos.example.test:2283"))
        assertEquals("https://photos.example.test/immich", ServerUrl.normalize("https://photos.example.test/immich/"))
        assertEquals("https://192.168.1.10:2283", ServerUrl.normalize("192.168.1.10:2283"))
        assertEquals("https://[fd00::1]:2283", ServerUrl.normalize("https://[fd00::1]:2283"))
    }

    @Test fun `the canonical form is built from what a request would use`() {
        assertEquals("https://photos.example.test", ServerUrl.canonical("https://photos.example.test"))
        assertEquals("https://photos.example.test", ServerUrl.canonical(" photos.example.test/ "))
        assertEquals("https://photos.example.test:2283", ServerUrl.canonical("https://photos.example.test:2283/"))
        // the default port says nothing; the host is lower case
        assertEquals("https://photos.example.test", ServerUrl.canonical("https://PHOTOS.Example.test:443"))
        assertEquals("https://photos.example.test/immich", ServerUrl.canonical("https://photos.example.test/immich/"))
        assertEquals("https://photos.example.test:8443/a/b", ServerUrl.canonical("photos.example.test:8443/a/b"))
        assertEquals("https://[fd00::1]:2283", ServerUrl.canonical("https://[FD00::1]:2283"))
        assertEquals("", ServerUrl.canonical("   "))
    }

    @Test fun `the canonical form has no query and no fragment`() {
        assertEquals("https://photos.example.test/immich", ServerUrl.canonical("https://photos.example.test/immich?next=x#top"))
        assertEquals("https://evil.example", ServerUrl.canonical("https://evil.example#@photos.example.test"))
        assertEquals("https://evil.example", ServerUrl.canonical("https://evil.example?@photos.example.test"))
    }

    @Test fun `an internationalized host is canonical in its punycode form`() {
        // the first letter is Cyrillic
        assertEquals("https://xn--hotos-uye.example.test", ServerUrl.canonical("https://рhotos.example.test"))
    }

    @Test fun `a path outside ASCII is canonical percent-encoded`() =
        assertEquals("https://photos.example.test/%D1%84", ServerUrl.canonical("https://photos.example.test/ф"))

    @Test fun `a backslash cannot hide the host`() {
        // OkHttp reads '\' as '/': the host is the first part, and that is what is shown
        assertEquals("https://evil.example/@photos.example.test", ServerUrl.canonical("https://evil.example\\@photos.example.test"))
    }

    @Test fun `what normalize rejects has no canonical form`() {
        for (address in disguised + listOf("http://photos.example.test", "ftp://host", "not a url")) {
            assertNull(ServerUrl.canonical(address), address)
        }
    }

    @Test fun `the canonical form is stable and passes as typed input`() {
        for (raw in listOf("photos.example.test", "https://PHOTOS.example.test:8443/a/b/", "https://рhotos.example.test/ф", "https://[fd00::1]:2283")) {
            val canonical = ServerUrl.canonical(raw)!!
            assertEquals(canonical, ServerUrl.canonical(canonical), raw)
            assertEquals(canonical, ServerUrl.normalize(canonical), raw)
        }
    }

    @Test fun `the host is named with its port unless that is the default`() {
        assertEquals("photos.example.test", ServerUrl.hostAndPort("https://photos.example.test"))
        assertEquals("photos.example.test", ServerUrl.hostAndPort("https://photos.example.test/immich"))
        assertEquals("photos.example.test:2283", ServerUrl.hostAndPort("https://photos.example.test:2283/immich"))
        assertEquals("[fd00::1]:2283", ServerUrl.hostAndPort("https://[fd00::1]:2283"))
        assertEquals("xn--hotos-uye.example.test", ServerUrl.hostAndPort("https://рhotos.example.test"))
        assertNull(ServerUrl.hostAndPort(""))
        assertNull(ServerUrl.hostAndPort("https://photos.example.test@evil.example"))
    }

    // Characters that IDN mapping turns into dots or digit-plus-dot: next to a real dot they
    // leave an empty label, so the host parses as written but not once rebuilt.
    private val dotLike = listOf(0x2024, 0x2025, 0x2026, 0x2488, 0x249B, 0x33C2, 0x33C7, 0x33D8, 0xFE30, 0xFE52)

    @Test fun `a canonical form is only ever one that parses back to itself`() {
        val hosts = dotLike.flatMap { code ->
            val c = code.toChar()
            listOf("photos.example.test$c.evil.example", "photos.example.test.$c.evil.example", "photos$c.example.test", "${c}photos.example.test")
        }
        for (host in hosts) for (raw in listOf("https://$host", "https://$host:8443/immich")) {
            val canonical = ServerUrl.canonical(raw) ?: continue
            assertEquals(canonical, ServerUrl.normalize(canonical), raw)
            assertEquals(canonical, ServerUrl.canonical(canonical), raw)
            kotlin.test.assertNotNull(ServerUrl.hostAndPort(canonical), raw)
        }
    }

    @Test fun `format characters outside the basic plane are rejected`() {
        for (code in listOf(0xE0001, 0xE0020, 0xE007F, 0x1D173, 0x1D17A, 0x110BD, 0x13430, 0x1BCA0)) {
            val c = String(Character.toChars(code))
            assertNull(ServerUrl.normalize("https://photos.example.test/a${c}b"), "path U+%X".format(code))
            assertNull(ServerUrl.normalize("https://pho${c}tos.example.test"), "host U+%X".format(code))
            assertNull(ServerUrl.canonical("https://photos.example.test/a${c}b"), "path U+%X".format(code))
        }
    }

    @Test fun `other characters outside the basic plane are not mistaken for format characters`() =
        assertEquals("https://photos.example.test/%F0%9F%93%B7", ServerUrl.canonical("https://photos.example.test/📷"))
}
