/*
 * Copyright (C) 2017 VSCT
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.vsct.tock.bot.connector.messenger

import com.fasterxml.jackson.module.kotlin.readValue
import fr.vsct.tock.bot.connector.ConnectorBase
import fr.vsct.tock.bot.connector.messenger.model.Recipient
import fr.vsct.tock.bot.connector.messenger.model.send.ActionRequest
import fr.vsct.tock.bot.connector.messenger.model.send.MessageRequest
import fr.vsct.tock.bot.connector.messenger.model.send.SendResponse
import fr.vsct.tock.bot.connector.messenger.model.send.SenderAction.mark_seen
import fr.vsct.tock.bot.connector.messenger.model.send.SenderAction.typing_off
import fr.vsct.tock.bot.connector.messenger.model.send.SenderAction.typing_on
import fr.vsct.tock.bot.connector.messenger.model.webhook.CallbackRequest
import fr.vsct.tock.bot.engine.BotRepository.requestTimer
import fr.vsct.tock.bot.engine.ConnectorController
import fr.vsct.tock.bot.engine.action.Action
import fr.vsct.tock.bot.engine.monitoring.logError
import fr.vsct.tock.bot.engine.user.PlayerId
import fr.vsct.tock.bot.engine.user.PlayerType.bot
import fr.vsct.tock.bot.engine.user.UserPreferences
import fr.vsct.tock.shared.defaultLocale
import fr.vsct.tock.shared.error
import fr.vsct.tock.shared.jackson.mapper
import fr.vsct.tock.shared.vertx.vertx
import mu.KotlinLogging
import org.apache.commons.codec.binary.Hex
import org.apache.commons.lang3.LocaleUtils
import java.lang.Exception
import java.time.ZoneOffset
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 *
 */
class MessengerConnector internal constructor(
        applicationId: String,
        val path: String,
        pageId: String,
        val token: String,
        val verifyToken: String?,
        val client: MessengerClient) : ConnectorBase(MessengerConnectorProvider.connectorType) {

    companion object {
        private val logger = KotlinLogging.logger {}
        private val pageApplicationMap: MutableMap<String, String> = mutableMapOf()
        private val applicationTokenMap: MutableMap<String, String> = mutableMapOf()
    }

    init {
        pageApplicationMap.put(pageId, applicationId)
        applicationTokenMap.put(applicationId, token)
    }

    override fun register(controller: ConnectorController) {
        controller.registerServices(path, { router ->
            logger.info("deploy rest messenger connector services for root path $path ")

            //see https://developers.facebook.com/docs/graph-api/webhooks
            router.get(path).handler { context ->
                try {
                    logger.info { "get facebook Message" }
                    if (verifyToken == null || verifyToken == context.request().getParam("hub.verify_token")) {
                        context.response().end(context.request().getParam("hub.challenge"))
                    } else {
                        context.response().end("Invalid verify token")
                    }
                } catch(e: Throwable) {
                    logger.error(e)
                    context.fail(500)
                }
            }

            router.post(path).handler { context ->
                val requestTimerData = requestTimer.start("messenger_webhook")
                try {
                    val facebookHeader = context.request().getHeader("X-Hub-Signature")
                    logger.debug { "Facebook signature:  $facebookHeader" }
                    logger.debug { "Facebook headers:  ${context.request().headers().entries()}" }
                    val body = context.bodyAsString
                    if (facebookHeader != null && isSignedByFacebook(body, facebookHeader)) {
                        try {
                            logger.debug { "Facebook request input : $body" }
                            val request = mapper.readValue<CallbackRequest>(body)

                            vertx.executeBlocking<Void>({
                                try {
                                    request.entry.forEach { entry ->
                                        try {
                                            if (entry.messaging?.isNotEmpty() ?: false) {

                                                val applicationId = pageApplicationMap.getValue(entry.id)
                                                entry.messaging!!.forEach { m ->
                                                    try {
                                                        val event = WebhookActionConverter.toAction(m, applicationId)
                                                        if (event != null) {
                                                            controller.handle(event)
                                                        } else {
                                                            logger.logError("unable to convert $m to event", requestTimerData)
                                                        }
                                                    } catch(e: Throwable) {
                                                        try {
                                                            logger.logError(e, requestTimerData)
                                                            controller.errorMessage(m.playerId(bot), applicationId, m.recipientId(bot)).let {
                                                                send(it)
                                                                endTypingAnswer(it)
                                                            }
                                                        } catch(t: Throwable) {
                                                            logger.error(e)
                                                        }
                                                    }
                                                }
                                            } else {
                                                logger.warn { "empty message for entry $entry" }
                                            }
                                        } catch(e: Throwable) {
                                            logger.logError(e, requestTimerData)
                                        }
                                    }
                                } catch(e: Throwable) {
                                    logger.logError(e, requestTimerData)
                                } finally {
                                    it.complete()
                                }
                            }, false, {})
                        } catch(t: Throwable) {
                            logger.logError(t, requestTimerData)
                        }
                    } else {
                        logger.logError("Not signed by facebook!!! : $facebookHeader \n $body", requestTimerData)
                    }

                } catch(e: Throwable) {
                    logger.logError(e, requestTimerData)
                } finally {
                    try {
                        requestTimer.end(requestTimerData)
                        context.response().end()
                    } catch(e: Throwable) {
                        logger.error(e)
                    }
                }
            }
        })
    }

    /**
     * Send an action to messenger.
     *
     * @param action the action to send
     * @param transformAction method to transform the request before sending - default is identity
     * @param postAction method (with token parameter) launched after successful call - default do nothing
     */
    fun sendAction(
            action: Action,
            transformAction: (MessageRequest) -> MessageRequest = { it },
            postAction: (String) -> Unit = {}): SendResponse? {
        return try {
            var message = SendActionConverter.toMessageRequest(action)
            if (message != null) {
                message = transformAction.invoke(message)
                logger.debug { "message sent: $message to ${action.recipientId}" }
                val token = getToken(action)
                val response = client.sendMessage(token, message)
                postAction.invoke(token)
                response
            } else {
                logger.error { "unable to convert $action to message" }
                null
            }
        } catch(e: Throwable) {
            logger.error(e)
            null
        }
    }

    /**
     * Send the first action after an optin request, using the recipient.user_ref property.
     * See https://developers.facebook.com/docs/messenger-platform/plugin-reference/checkbox-plugin#implementation for more details.
     *
     * @param action the action to send
     * @return the real user id, null if error
     */
    fun sendActionAfterOptIn(action: Action): String? {
        val response = sendAction(
                action,
                { request ->
                    //need to use the user_ref here
                    request.copy(recipient = Recipient(null, request.recipient.id))
                })
        return response?.recipientId
    }

    override fun send(action: Action) {
        sendAction(
                action,
                postAction =
                { token ->
                    val recipient = Recipient(action.recipientId.id)
                    if (action.botMetadata.lastAnswer) {
                        client.sendAction(token, ActionRequest(recipient, typing_off))
                        client.sendAction(token, ActionRequest(recipient, mark_seen))
                    } else {
                        client.sendAction(token, ActionRequest(recipient, typing_on))
                    }
                }
        )
    }

    override fun startTypingInAnswerTo(action: Action) {
        client.sendAction(getToken(action), ActionRequest(Recipient(action.playerId.id), typing_on))
    }

    fun endTypingAnswer(action: Action) {
        client.sendAction(getToken(action), ActionRequest(Recipient(action.recipientId.id), typing_off))
    }

    override fun loadProfile(applicationId: String, userId: PlayerId): UserPreferences {
        try {
            val userProfile = client.getUserProfile(applicationTokenMap.getValue(applicationId), Recipient(userId.id))
            logger.debug { "User profile : $userProfile for $userId" }
            return UserPreferences(
                    userProfile.firstName,
                    userProfile.lastName,
                    null,
                    ZoneOffset.ofHours(userProfile.timezone),
                    userProfile.locale?.let {
                        try {
                            LocaleUtils.toLocale(it)
                        } catch(e: Exception) {
                            logger.error(e)
                            null
                        }
                    } ?: defaultLocale,
                    userProfile.profilePic,
                    userProfile.gender)
        } catch(e: Exception) {
            logger.error(e)
        }
        return UserPreferences()
    }

    private fun getToken(action: Action): String {
        return applicationTokenMap.getValue(action.applicationId)
    }

    private fun isSignedByFacebook(payload: String, facebookSignature: String): Boolean {
        return "sha1=${sha1(payload, client.secretKey)}" == facebookSignature
    }

    private fun sha1(payload: String, key: String): String {
        val k = SecretKeySpec(key.toByteArray(), "HmacSHA1")
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(k)

        val bytes = mac.doFinal(payload.toByteArray())

        return String(Hex().encode(bytes))
    }
}