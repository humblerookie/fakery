package dev.anvith.fakery

/**
 * Finds the first stub that matches [incoming] and advances its sequence counter.
 *
 * The [IncomingRequest] is parsed **once** by the caller ([StubRegistry.match])
 * before this function is called, so body parsing happens at most once per request
 * regardless of how many stubs are evaluated.
 *
 * @return The next [StubResponse] from the matched stub, or `null` if nothing matched.
 */
internal fun matchStub(incoming: IncomingRequest, stubs: List<StatefulEntry>): StubResponse? =
    stubs.firstOrNull { it.matches(incoming) }?.nextResponse()
