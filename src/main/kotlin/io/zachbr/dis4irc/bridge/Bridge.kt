/*
 * This file is part of Dis4IRC.
 *
 * Copyright (c) 2018-2021 Dis4IRC contributors
 *
 * MIT License
 */

package io.zachbr.dis4irc.bridge

import io.zachbr.dis4irc.Dis4IRC
import io.zachbr.dis4irc.bridge.command.COMMAND_PREFIX
import io.zachbr.dis4irc.bridge.command.CommandManager
import io.zachbr.dis4irc.bridge.message.Message
import io.zachbr.dis4irc.bridge.message.PlatformType
import io.zachbr.dis4irc.bridge.mutator.MutatorManager
import io.zachbr.dis4irc.bridge.pier.discord.DiscordPier
import io.zachbr.dis4irc.bridge.pier.irc.IrcPier
import org.json.JSONObject
import org.slf4j.LoggerFactory

/**
 * Responsible for the connection between Discord and IRC, including message processing hand offs
 */
class Bridge(private val main: Dis4IRC, internal val config: BridgeConfiguration) {
    internal val logger = LoggerFactory.getLogger(config.bridgeName) ?: throw IllegalStateException("Could not init logger")

    internal val channelMappings = ChannelMappingManager(config)
    internal val statsManager = StatisticsManager(this)
    private val commandManager = CommandManager(this, config.rawNode.node("commands"))
    internal val mutatorManager = MutatorManager(this, config.rawNode.node("mutators"))

    internal val discordConn = DiscordPier(this)
    internal val ircConn = IrcPier(this)

    /**
     * Connects to IRC and Discord
     */
    fun startBridge() {
        logger.debug(config.toLoggable())

        try {
            discordConn.start()
            ircConn.start()
        } catch (ex: Exception) { // just catch everything - "conditions that a reasonable application might want to catch"
            logger.error("Unable to initialize bridge connections: $ex")
            ex.printStackTrace()
            this.shutdown(inErr = true)
            return
        }

        logger.info("Bridge initialized and running")
    }

    /**
     * Bridges communication between the two piers
     */
    internal fun submitMessage(messageIn: Message) {
        val bridgeTarget: String? = channelMappings.getMappingFor(messageIn.source)

        if (bridgeTarget == null) {
            logger.debug("Discarding message with no bridge target from: ${messageIn.source}")
            return
        }

        // mutate message contents
        val mutatedMessage = mutatorManager.applyMutators(messageIn) ?: return

        if (mutatedMessage.shouldSendTo(PlatformType.IRC)) {
            val target: String = if (mutatedMessage.source.type == PlatformType.IRC) mutatedMessage.source.channelName else bridgeTarget
            ircConn.sendMessage(target, mutatedMessage)
        }

        if (mutatedMessage.shouldSendTo(PlatformType.DISCORD)) {
            val target = if (mutatedMessage.source.type == PlatformType.DISCORD) mutatedMessage.source.discordId.toString() else bridgeTarget
            discordConn.sendMessage(target, mutatedMessage)
        }

        // command handling
        if (mutatedMessage.contents.startsWith(COMMAND_PREFIX) && !mutatedMessage.originatesFromBridgeItself()) {
            commandManager.processCommandMessage(mutatedMessage)
        }
    }

    /**
     * Clean up and disconnect from the IRC and Discord platforms
     */
    internal fun shutdown(inErr: Boolean = false) {
        logger.debug("Stopping bridge...")

        discordConn.onShutdown()
        ircConn.onShutdown()

        logger.info("Bridge stopped")
        main.notifyOfBridgeShutdown(this, inErr)
    }

    internal fun persistData(json: JSONObject): JSONObject {
        json.put("statistics", statsManager.writeData(JSONObject()))
        return json
    }

    internal fun readSavedData(json: JSONObject) {
        if (json.has("statistics")) {
            statsManager.readSavedData(json.getJSONObject("statistics"))
        }
    }

    /**
     * Adds a message's handling time to the bridge's collection for monitoring purposes
     */
    fun updateStatistics(message: Message, timestampOut: Long) {
        statsManager.processMessage(message, timestampOut)
    }
}
