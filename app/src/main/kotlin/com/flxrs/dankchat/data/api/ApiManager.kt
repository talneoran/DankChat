package com.flxrs.dankchat.data.api

import android.util.Log
import com.flxrs.dankchat.BuildConfig
import com.flxrs.dankchat.data.api.dto.*
import com.flxrs.dankchat.preferences.DankChatPreferenceStore
import io.ktor.client.call.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import retrofit2.Response
import java.io.File
import java.net.URLConnection
import java.time.Instant
import javax.inject.Inject

class ApiManager @Inject constructor(
    private val client: OkHttpClient,
    private val bttvApiService: BTTVApiService,
    private val dankChatApiService: DankChatApiService,
    private val ffzApiService: FFZApiService,
    private val helixApiService: HelixApiService,
    private val recentMessagesApiService: RecentMessagesApiService,
    private val supibotApiService: SupibotApiService,
    private val authApiService: AuthApiService,
    private val badgesApiService: BadgesApiService,
    private val tmiApiService: TmiApiService,
    private val sevenTVApiService: SevenTVApiService,
    private val dankChatPreferenceStore: DankChatPreferenceStore
) {

    suspend fun validateUser(oAuth: String): ValidateUserDto? {
        try {
            val response = authApiService.validateUser("OAuth $oAuth")
            if (response.isSuccessful) return response.body()
        } catch (t: Throwable) {
            Log.e(TAG, Log.getStackTraceString(t))
        }
        return null
    }

    suspend fun getUserIdByName(name: String): String? {
        return helixApiService.getUserByName(listOf(name))
            ?.bodyOrNull<HelixUsersDto>()
            ?.data?.firstOrNull()?.id
    }
    suspend fun getUsersByNames(names: List<String>): List<HelixUserDto>? {
        return helixApiService.getUserByName(names)
            ?.bodyOrNull<HelixUsersDto>()
            ?.data
    }
    suspend fun getUser(userId: String): HelixUserDto? {
        return helixApiService.getUserById(userId)
            ?.bodyOrNull<HelixUsersDto>()
            ?.data?.firstOrNull()
    }
    suspend fun getUsersFollows(fromId: String, toId: String): UserFollowsDto? {
        return helixApiService.getUsersFollows(fromId, toId)
            ?.bodyOrNull()
    }
    suspend fun getStreams(channels: List<String>): StreamsDto? {
        return helixApiService.getStreams(channels)
            ?.bodyOrNull()
    }
    suspend fun getUserBlocks(userId: String): HelixUserBlockListDto? {
        return helixApiService.getUserBlocks(userId)
            ?.bodyOrNull()
    }
    suspend fun blockUser(targetUserId: String): Boolean {
        return helixApiService.putUserBlock(targetUserId)
            ?.status?.isSuccess() ?: false
    }
    suspend fun unblockUser(targetUserId: String): Boolean {
        return helixApiService.deleteUserBlock(targetUserId)
            ?.status?.isSuccess() ?: false
    }
    suspend fun getChannelBadges(channelId: String): HelixBadgesDto? {
        return helixApiService.getChannelBadges(channelId)
            ?.bodyOrNull()
    }
    suspend fun getGlobalBadges(): HelixBadgesDto? {
        return helixApiService.getGlobalBadges()
            ?.bodyOrNull()
    }

    suspend fun getUserSets(sets: List<String>): List<DankChatEmoteSetDto>? = dankChatApiService.getSets(sets.joinToString(separator = ",")).bodyOrNull()
    suspend fun getDankChatBadges(): List<DankChatBadgeDto>? = dankChatApiService.getDankChatBadges().bodyOrNull()

    suspend fun getChannelBadgesFallback(channelId: String): TwitchBadgesDto? = badgesApiService.getChannelBadges(channelId).bodyOrNull
    suspend fun getGlobalBadgesFallback(): TwitchBadgesDto? = badgesApiService.getGlobalBadges().bodyOrNull

    suspend fun getFFZChannelEmotes(channelId: String): FFZChannelDto? = ffzApiService.getChannelEmotes(channelId).bodyOrNull
    suspend fun getFFZGlobalEmotes(): FFZGlobalDto? = ffzApiService.getGlobalEmotes().bodyOrNull

    suspend fun getBTTVChannelEmotes(channelId: String): BTTVChannelDto? = bttvApiService.getChannelEmotes(channelId).bodyOrNull
    suspend fun getBTTVGlobalEmotes(): List<BTTVGlobalEmotesDto>? = bttvApiService.getGlobalEmotes().bodyOrNull

    suspend fun getSevenTVChannelEmotes(channelId: String): List<SevenTVEmoteDto>? = sevenTVApiService.getChannelEmotes(channelId).bodyOrNull
    suspend fun getSevenTVGlobalEmotes(): List<SevenTVEmoteDto>? = sevenTVApiService.getGlobalEmotes().bodyOrNull

    suspend fun getRecentMessages(channel: String) = recentMessagesApiService.getRecentMessages(channel)

    suspend fun getSupibotCommands(): SupibotCommandsDto? = supibotApiService.getCommands().bodyOrNull
    suspend fun getSupibotChannels(): SupibotChannelsDto? = supibotApiService.getChannels("twitch").bodyOrNull
    suspend fun getSupibotUserAliases(user: String) = supibotApiService.getUserAliases(user).bodyOrNull

    suspend fun getChatters(channel: String): ChattersDto? = tmiApiService.getChatters(channel).bodyOrNull<ChattersResultDto>()?.chatters
    suspend fun getChatterCount(channel: String): Int? = tmiApiService.getChatters(channel).bodyOrNull<ChatterCountDto>()?.chatterCount

    @Suppress("BlockingMethodInNonBlockingContext")
    suspend fun uploadMedia(file: File): UploadDto? = withContext(Dispatchers.IO) {
        val uploader = dankChatPreferenceStore.customImageUploader
        val extension = file.extension.ifBlank { "png" }
        val mimetype = URLConnection.guessContentTypeFromName(file.name)
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(uploader.formField, filename = "${uploader.formField}.$extension", body = file.asRequestBody(mimetype.toMediaType()))
            .build()

        val request = Request.Builder()
            .url(uploader.uploadUrl)
            .header("User-Agent", "dankchat/${BuildConfig.VERSION_NAME}")
            .apply {
                uploader.parsedHeaders.forEach { (name, value) ->
                    header(name, value)
                }
            }
            .post(body)
            .build()

        val response = client.newCall(request).execute()
        when {
            response.isSuccessful -> {
                val imageLinkPattern = uploader.imageLinkPattern
                val deletionLinkPattern = uploader.deletionLinkPattern

                if (imageLinkPattern == null) {
                    return@withContext response.bodyOrNull?.let {
                        UploadDto(
                            imageLink = it,
                            deleteLink = null,
                            timestamp = Instant.now()
                        )
                    }
                }

                val json = response.jsonObjectOrNull ?: return@withContext null
                val deleteLink = deletionLinkPattern?.let { json.extractLink(it) }
                val imageLink = json.extractLink(imageLinkPattern)

                UploadDto(
                    imageLink = imageLink,
                    deleteLink = deleteLink,
                    timestamp = Instant.now()
                )
            }
            else                  -> null
        }
    }

    @Suppress("RegExpRedundantEscape")
    private suspend fun JSONObject.extractLink(linkPattern: String): String = withContext(Dispatchers.Default) {
        var imageLink: String = linkPattern

        val regex = "\\{(.+)\\}".toRegex()
        regex.findAll(linkPattern).forEach {
            val jsonValue = getValue(it.groupValues[1])
            if (jsonValue != null) {
                imageLink = imageLink.replace(it.groupValues[0], jsonValue)
            }
        }
        imageLink
    }

    private val okhttp3.Response.bodyOrNull: String?
        get() = runCatching {
            body?.string()
        }.getOrNull()

    private val okhttp3.Response.jsonObjectOrNull: JSONObject?
        get() = runCatching {
            val bodyString = bodyOrNull ?: return@runCatching null
            JSONObject(bodyString)
        }.getOrNull()

    private fun JSONObject.getValue(pattern: String): String? {
        return runCatching {
            pattern
                .split(".")
                .fold(this) { acc, key ->
                    val value = acc.get(key)
                    if (value !is JSONObject) {
                        return value.toString()
                    }

                    value
                }
            null
        }.getOrNull()
    }

    companion object {
        private val TAG = ApiManager::class.java.simpleName

        private const val BASE_LOGIN_URL = "https://id.twitch.tv/oauth2/authorize?response_type=token"
        private const val REDIRECT_URL = "https://flxrs.com/dankchat"
        private const val SCOPES = "chat:edit" +
                "+chat:read" +
                "+whispers:read" +
                "+whispers:edit" +
                "+channel_editor" +
                "+channel_commercial" +
                "+channel:moderate" +
                "+channel:edit:commercial" +
                //"+channel:manage:broadcast" +
                "+channel:read:redemptions" +
                //"+moderator:manage:automod" +
                // "+clips:edit" +
                "+user_read" +
                "+user_subscriptions" +
                "+user_blocks_read" +
                "+user_blocks_edit" +
                "+user:edit:follows" +
                "+user:read:blocked_users" +
                "+user:manage:blocked_users"
        const val CLIENT_ID = "xu7vd1i6tlr0ak45q1li2wdc0lrma8"
        const val LOGIN_URL = "$BASE_LOGIN_URL&client_id=$CLIENT_ID&redirect_uri=$REDIRECT_URL&scope=$SCOPES"
    }
}

private val <T> Response<T>.bodyOrNull: T?
    get() = when {
        isSuccessful -> body()
        else         -> null
    }

suspend inline fun <reified T> HttpResponse.bodyOrNull(): T? = runCatching { body<T>() }.getOrNull()
