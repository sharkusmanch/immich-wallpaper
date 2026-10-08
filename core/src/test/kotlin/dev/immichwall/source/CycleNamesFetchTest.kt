package dev.immichwall.source

import dev.immichwall.api.ImmichApiClient
import dev.immichwall.source.CycleNames.ListOutcome
import dev.immichwall.source.CycleNames.Needed
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [CycleNames.fetch] against a mock server. The names step runs at every sync and is not
 * worth a failed one: whatever a list request does short of a bug, the sync goes on with
 * the names it has.
 */
class CycleNamesFetchTest {
    private val ann = "00000000-0000-4000-8000-000000000001"
    private val bob = "00000000-0000-4000-8000-000000000002"
    private val cat = "00000000-0000-4000-8000-000000000003"
    private val album = "00000000-0000-4000-8000-0000000000a1"

    private val both = Needed(albums = true, people = true)
    private val albumsJson = """[{"id":"$album","albumName":"Journeys","assetCount":3}]"""
    private val unparseable = """[{"id":"$album","albumName":"Journeys","assetCount":"""

    private lateinit var server: MockWebServer

    @BeforeTest fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @AfterTest fun tearDown() {
        server.shutdown()
    }

    private fun client(limits: ImmichApiClient.Limits = ImmichApiClient.Limits()) =
        ImmichApiClient({ server.url("/").toString() }, { "secret-key" }, limits)

    private fun peoplePage(hasNextPage: Boolean, vararg people: Pair<String, String>): MockResponse {
        val list = people.joinToString(",") { (id, name) -> """{"id":"$id","name":"$name"}""" }
        return MockResponse().setBody("""{"people":[$list],"total":${people.size},"hasNextPage":$hasNextPage}""")
    }

    /** The paths of every request the server has received so far, in order. */
    private fun requestedPaths(): List<String> = List(server.requestCount) { server.takeRequest().path.orEmpty() }

    private fun fetch(
        needed: Needed = both,
        maxPeoplePages: Int = 20,
        client: ImmichApiClient = client(),
        isStopped: () -> Boolean = { false },
    ) = CycleNames.fetch(client, needed, maxPeoplePages, isStopped)

    // --- both lists arrive ---

    @Test fun `both lists are read and returned by id`() {
        server.enqueue(MockResponse().setBody(albumsJson))
        server.enqueue(peoplePage(false, ann to "Anna"))
        val fetched = fetch()
        assertEquals(mapOf(album to "Journeys"), fetched.albumNames)
        assertEquals(mapOf(ann to "Anna"), fetched.personNames)
        assertEquals(ListOutcome.Complete, fetched.albums)
        assertEquals(ListOutcome.Complete, fetched.people)
        assertEquals(listOf("/api/albums", "/api/people?page=1&size=500&withHidden=false"), requestedPaths())
    }

    @Test fun `two people pages are merged`() {
        server.enqueue(MockResponse().setBody(albumsJson))
        server.enqueue(peoplePage(true, ann to "Anna"))
        server.enqueue(peoplePage(false, bob to "Robert"))
        val fetched = fetch()
        assertEquals(mapOf(ann to "Anna", bob to "Robert"), fetched.personNames)
        assertEquals(ListOutcome.Complete, fetched.people)
        assertEquals(
            listOf("/api/albums", "/api/people?page=1&size=500&withHidden=false", "/api/people?page=2&size=500&withHidden=false"),
            requestedPaths(),
        )
    }

    // --- a list that fails ---

    @Test fun `an HTTP 500 on the album list gives no album names and the people list is still requested`() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"message":"boom"}"""))
        server.enqueue(peoplePage(false, ann to "Anna"))
        val fetched = fetch()
        assertEquals(emptyMap(), fetched.albumNames)
        assertEquals(ListOutcome.HttpError(500), fetched.albums)
        assertEquals(mapOf(ann to "Anna"), fetched.personNames)
        assertEquals(ListOutcome.Complete, fetched.people)
        assertEquals(2, server.requestCount)
    }

    @Test fun `an unparseable album body gives no album names, does not throw, and the people list is not requested`() {
        server.enqueue(MockResponse().setBody(unparseable))
        server.enqueue(peoplePage(false, ann to "Anna"))
        val fetched = fetch()
        assertEquals(emptyMap(), fetched.albumNames)
        assertEquals(ListOutcome.Failed("java.io.IOException"), fetched.albums)
        assertEquals(emptyMap(), fetched.personNames)
        assertEquals(ListOutcome.Skipped, fetched.people)
        assertEquals(1, server.requestCount)
    }

    @Test fun `an empty album body and one over the size cap end the same way`() {
        server.enqueue(MockResponse().setBody(""))
        val empty = fetch()
        assertEquals(emptyMap(), empty.albumNames)
        assertIs<ListOutcome.Failed>(empty.albums)
        assertEquals(ListOutcome.Skipped, empty.people)

        server.enqueue(MockResponse().setBody("[" + " ".repeat(500) + "]"))
        val oversized = fetch(client = client(ImmichApiClient.Limits(maxJsonBytes = 64)))
        assertEquals(emptyMap(), oversized.albumNames)
        assertIs<ListOutcome.Failed>(oversized.albums)
        assertEquals(ListOutcome.Skipped, oversized.people)

        assertEquals(listOf("/api/albums", "/api/albums"), requestedPaths())
    }

    @Test fun `a connection that drops on the album list gives no album names and the people list is not requested`() {
        // OkHttp may try the request again on its own; what matters is where it never goes.
        repeat(4) { server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)) }
        val fetched = fetch()
        assertEquals(emptyMap(), fetched.albumNames)
        assertIs<ListOutcome.Failed>(fetched.albums)
        assertEquals(emptyMap(), fetched.personNames)
        assertEquals(ListOutcome.Skipped, fetched.people)
        val paths = requestedPaths()
        assertTrue(paths.isNotEmpty() && paths.all { it == "/api/albums" }, paths.toString())
    }

    @Test fun `a failed album list with no people list needed asks for nothing more`() {
        server.enqueue(MockResponse().setBody(unparseable))
        val fetched = fetch(Needed(albums = true, people = false))
        assertIs<ListOutcome.Failed>(fetched.albums)
        assertEquals(ListOutcome.NotNeeded, fetched.people)
        assertEquals(1, server.requestCount)
    }

    @Test fun `an unparseable second people page gives no people names at all and the album names are kept`() {
        server.enqueue(MockResponse().setBody(albumsJson))
        server.enqueue(peoplePage(true, ann to "Anna"))
        server.enqueue(MockResponse().setBody("""{"people":[{"id":"$bob","name":"Robert"}"""))
        val fetched = fetch()
        assertEquals(mapOf(album to "Journeys"), fetched.albumNames)
        assertEquals(ListOutcome.Complete, fetched.albums)
        // Not the first page's names: a list that was only partly read is not applied.
        assertEquals(emptyMap(), fetched.personNames)
        assertEquals(ListOutcome.Failed("java.io.IOException"), fetched.people)
        assertEquals(3, server.requestCount)
    }

    @Test fun `an HTTP error on a people page gives no people names and the album names are kept`() {
        server.enqueue(MockResponse().setBody(albumsJson))
        server.enqueue(peoplePage(true, ann to "Anna"))
        server.enqueue(MockResponse().setResponseCode(403))
        val fetched = fetch()
        assertEquals(mapOf(album to "Journeys"), fetched.albumNames)
        assertEquals(emptyMap(), fetched.personNames)
        assertEquals(ListOutcome.HttpError(403), fetched.people)
    }

    /** The client's message for a body it cannot parse can quote the body, names included. */
    @Test fun `what is reported of a failure holds nothing the server sent`() {
        server.enqueue(MockResponse().setBody("""[{"id":"$album","albumName":"Journeys","assetCount":"many"}]"""))
        val albums = fetch(Needed(albums = true, people = false)).albums
        assertEquals(ListOutcome.Failed("java.io.IOException"), albums)
        assertFalse(albums.toString().contains("Journeys"))

        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"message":"Journeys is broken"}"""))
        val http = fetch(Needed(albums = true, people = false)).albums
        assertEquals(ListOutcome.HttpError(500), http)
        assertFalse(http.toString().contains("Journeys"))
    }

    @Test fun `an exception that is not an I-O failure is not swallowed`() {
        server.enqueue(MockResponse().setBody(albumsJson))
        server.enqueue(peoplePage(true, ann to "Anna"))
        assertFailsWith<IllegalStateException> { fetch(isStopped = { error("a bug") }) }
    }

    // --- paging ---

    @Test fun `the page cap stops the paging and is reported`() {
        server.enqueue(peoplePage(true, ann to "Anna"))
        server.enqueue(peoplePage(true, bob to "Robert"))
        server.enqueue(peoplePage(false, cat to "Catherine"))
        val fetched = fetch(Needed(albums = false, people = true), maxPeoplePages = 2)
        assertEquals(ListOutcome.Capped(2), fetched.people)
        // The names that were read are good; those beyond the cap stay as stored.
        assertEquals(mapOf(ann to "Anna", bob to "Robert"), fetched.personNames)
        assertEquals(2, server.requestCount)
    }

    @Test fun `a list that ends on the cap page is complete`() {
        server.enqueue(peoplePage(true, ann to "Anna"))
        server.enqueue(peoplePage(false, bob to "Robert"))
        val fetched = fetch(Needed(albums = false, people = true), maxPeoplePages = 2)
        assertEquals(ListOutcome.Complete, fetched.people)
        assertEquals(mapOf(ann to "Anna", bob to "Robert"), fetched.personNames)
    }

    @Test fun `isStopped turning true stops the paging`() {
        server.enqueue(peoplePage(true, ann to "Anna"))
        server.enqueue(peoplePage(true, bob to "Robert"))
        server.enqueue(peoplePage(false, cat to "Catherine"))
        // Not stopped after the first page, stopped after the second.
        var asked = 0
        val fetched = fetch(Needed(albums = false, people = true), isStopped = { ++asked >= 2 })
        assertEquals(ListOutcome.Stopped, fetched.people)
        assertEquals(mapOf(ann to "Anna", bob to "Robert"), fetched.personNames)
        assertEquals(2, server.requestCount)
    }

    // --- only what is needed ---

    @Test fun `a list that is not needed is not requested`() {
        server.enqueue(peoplePage(false, ann to "Anna"))
        val peopleOnly = fetch(Needed(albums = false, people = true))
        assertEquals(ListOutcome.NotNeeded, peopleOnly.albums)
        assertEquals(emptyMap(), peopleOnly.albumNames)
        assertEquals(mapOf(ann to "Anna"), peopleOnly.personNames)

        server.enqueue(MockResponse().setBody(albumsJson))
        val albumsOnly = fetch(Needed(albums = true, people = false))
        assertEquals(ListOutcome.NotNeeded, albumsOnly.people)
        assertEquals(emptyMap(), albumsOnly.personNames)
        assertEquals(mapOf(album to "Journeys"), albumsOnly.albumNames)

        assertEquals(listOf("/api/people?page=1&size=500&withHidden=false", "/api/albums"), requestedPaths())

        val neither = fetch(Needed(albums = false, people = false))
        assertEquals(ListOutcome.NotNeeded, neither.albums)
        assertEquals(ListOutcome.NotNeeded, neither.people)
        assertEquals(2, server.requestCount)
    }
}
