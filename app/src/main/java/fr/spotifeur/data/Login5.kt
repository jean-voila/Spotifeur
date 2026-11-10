package fr.spotifeur.data

import android.os.SystemClock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** The server does not take this Login5 version or request layout; another version may. */
internal class Login5ProtocolRefused(message: String) : SpotifyFailure(message)

internal class StoredCredential(val username: String, val bytes: ByteArray)

internal class LoginGrant(val access: String, val expiresAt: Long, val credential: StoredCredential?) {
    fun valid() = SystemClock.elapsedRealtime() < expiresAt
}

internal class Login5(
    private val transport: SpotifyTransport,
    private val clientId: String,
    private val deviceId: String,
) {
    suspend fun email(
        clientToken: String,
        email: String,
        flowId: String,
        code: suspend (Int) -> String,
    ): LoginGrant = exchange(
        clientToken, 111, proto { writeString(1, email) },
        version = 4, flowId = flowId, requestCode = code,
    )

    /**
     * Password sign-in. The 9.2 app sends `NativePassword` (1 id, 2 password) as field 101 of a v4
     * request with a flow ID; v3 (`LegacyV3Password`, same field) is only used if v4 is refused as a protocol.
     */
    suspend fun password(
        clientToken: String,
        username: String,
        password: String,
        code: suspend (Int) -> String,
    ): LoginGrant {
        val credential = proto {
            writeString(1, username)
            writeString(2, password)
        }
        try {
            return try {
                exchange(clientToken, 101, credential, version = 4, flowId = java.util.UUID.randomUUID().toString(), requestCode = code)
            } catch (failure: SpotifyFailure) {
                if (failure !is Login5ProtocolRefused) throw failure
                exchange(clientToken, 101, credential, requestCode = code)
            }
        } finally {
            credential.fill(0)
        }
    }

    suspend fun restore(clientToken: String, saved: StoredCredential): LoginGrant =
        exchange(clientToken, 100, proto {
            writeString(1, saved.username)
            writeByteArray(2, saved.bytes)
        }) { throw SpotifyAuthRequired("Spotify requires signing in again.") }

    private suspend fun exchange(
        clientToken: String,
        method: Int,
        credentials: ByteArray,
        version: Int = 3,
        flowId: String? = null,
        requestCode: suspend (Int) -> String,
    ): LoginGrant {
        var context: ByteArray? = null
        var solutions = emptyList<ByteArray>()
        var codeSubmitted = false
        repeat(4) {
            val body = proto {
                writeByteArray(1, proto {
                    writeString(1, clientId)
                    writeString(2, deviceId)
                })
                context?.let { writeByteArray(2, it) }
                if (solutions.isNotEmpty()) {
                    writeByteArray(3, proto { solutions.forEach { writeByteArray(1, it) } })
                }
                if (version == 4 && flowId != null) writeString(6, flowId)
                writeByteArray(method, credentials)
            }
            val request = Request.Builder().url("https://login5.spotify.com/v$version/login")
                .header("Accept", "application/x-protobuf")
                .header("client-token", clientToken)
                .post(body.toRequestBody("application/x-protobuf".toMediaType()))
                .build()
            val response = try {
                Proto(transport.send(request, "Spotify sign-in"))
            } catch (failure: SpotifyFailure) {
                if (failure.status == 400) throw Login5ProtocolRefused("Spotify rejected Android login protocol v$version.")
                throw failure
            } finally {
                body.fill(0)
            }
            response.message(1)?.let(::readGrant)?.let { return it }
            response.number(2)?.let { error ->
                throw when (error) {
                    1 -> SpotifyFailure(
                        when {
                            method == 111 && codeSubmitted -> "Spotify rejected the login code. Start signing in again."
                            method == 111 -> "Spotify rejected the email sign-in."
                            else -> "Spotify rejected the credentials."
                        },
                        401,
                    )
                    3 -> Login5ProtocolRefused("Spotify rejected Android login protocol v$version.")
                    6, 8 -> SpotifyFailure("Spotify asks to try again later.", 429)
                    else -> SpotifyFailure("Spotify sign-in rejected (code $error).")
                }
            }

            val challengeContext = response.bytes(5) ?: throw SpotifyFailure("Missing Login5 context.")
            val challenges = response.message(3)?.messages(1).orEmpty()
            if (challenges.isEmpty() || challenges.size > 4) throw SpotifyFailure("Unexpected Login5 challenge.")
            context = challengeContext
            solutions = challenges.map { challenge ->
                val hashcash = challenge.message(1)
                if (hashcash != null) {
                    val prefix = hashcash.bytes(1) ?: throw SpotifyFailure("Invalid Hashcash challenge.")
                    val start = System.nanoTime()
                    val suffix = solveHashcash(challengeContext, prefix, hashcash.number(2) ?: 0)
                    val elapsed = System.nanoTime() - start
                    proto {
                        writeByteArray(1, proto {
                            writeByteArray(1, suffix)
                            writeByteArray(2, proto {
                                writeInt64(1, elapsed / 1_000_000_000L)
                                writeInt32(2, (elapsed % 1_000_000_000L).toInt())
                            })
                        })
                    }
                } else {
                    val code = challenge.message(2) ?: run {
                        val type = (3..10).firstOrNull { challenge.bytes(it) != null }
                        fr.spotifeur.data.diagnostics.DiagnosticLog.record("login", "unsupported challenge field=${type ?: "unknown"} v$version")
                        throw SpotifyFailure(
                            if (method == 101) "Spotify asks for a check Spotifeur cannot show. Sign in with a code sent by email instead."
                            else "Spotify requires an Android challenge that is not implemented yet.",
                        )
                    }
                    val length = code.number(2)?.takeIf { it in 1..12 } ?: 6
                    val value = requestCode(length)
                    codeSubmitted = true
                    proto { writeByteArray(2, proto { writeString(1, value) }) }
                }
            }
        }
        throw SpotifyFailure("Spotify keeps renewing the login challenge.")
    }

    private fun readGrant(ok: Proto): LoginGrant {
        val access = ok.text(2)?.takeIf { it.isNotBlank() && it.all { char -> char in '!'..'~' } }
            ?: throw SpotifyFailure("Invalid Login5 token.")
        val expiry = ok.number(4)?.takeIf { it > 0 } ?: throw SpotifyFailure("Invalid Login5 token lifetime.")
        val username = ok.text(1)?.takeIf(String::isNotBlank)
        val saved = ok.bytes(3)?.takeIf(ByteArray::isNotEmpty)?.let { bytes ->
            username?.let { StoredCredential(it, bytes) }
        }
        return LoginGrant(access, SystemClock.elapsedRealtime() + expiry * 1000L - minOf(30_000L, expiry * 100L), saved)
    }
}
