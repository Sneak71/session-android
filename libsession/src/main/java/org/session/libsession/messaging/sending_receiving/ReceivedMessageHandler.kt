package org.session.libsession.messaging.sending_receiving

import android.text.TextUtils
import org.session.libsession.avatars.AvatarHelper
import org.session.libsession.messaging.MessagingModuleConfiguration
import org.session.libsession.messaging.jobs.BackgroundGroupAddJob
import org.session.libsession.messaging.jobs.JobQueue
import org.session.libsession.messaging.messages.Message
import org.session.libsession.messaging.messages.control.*
import org.session.libsession.messaging.messages.visible.Attachment
import org.session.libsession.messaging.messages.visible.Reaction
import org.session.libsession.messaging.messages.visible.VisibleMessage
import org.session.libsession.messaging.open_groups.OpenGroupApi
import org.session.libsession.messaging.sending_receiving.attachments.PointerAttachment
import org.session.libsession.messaging.sending_receiving.data_extraction.DataExtractionNotificationInfoMessage
import org.session.libsession.messaging.sending_receiving.link_preview.LinkPreview
import org.session.libsession.messaging.sending_receiving.notifications.PushNotificationAPI
import org.session.libsession.messaging.sending_receiving.pollers.ClosedGroupPollerV2
import org.session.libsession.messaging.sending_receiving.quotes.QuoteModel
import org.session.libsession.messaging.utilities.SessionId
import org.session.libsession.messaging.utilities.SodiumUtilities
import org.session.libsession.messaging.utilities.WebRtcUtils
import org.session.libsession.snode.SnodeAPI
import org.session.libsession.utilities.*
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsignal.crypto.ecc.DjbECPrivateKey
import org.session.libsignal.crypto.ecc.DjbECPublicKey
import org.session.libsignal.crypto.ecc.ECKeyPair
import org.session.libsignal.messages.SignalServiceGroup
import org.session.libsignal.protos.SignalServiceProtos
import org.session.libsignal.utilities.*
import org.session.libsignal.utilities.Base64
import org.session.libsignal.utilities.guava.Optional
import java.security.MessageDigest
import java.util.*
import kotlin.math.min

internal fun MessageReceiver.isBlocked(publicKey: String): Boolean {
    val context = MessagingModuleConfiguration.shared.context
    val recipient = Recipient.from(context, Address.fromSerialized(publicKey), false)
    return recipient.isBlocked
}

fun MessageReceiver.handle(message: Message, proto: SignalServiceProtos.Content, openGroupID: String?) {
    when (message) {
        is ReadReceipt -> handleReadReceipt(message)
        is TypingIndicator -> handleTypingIndicator(message)
        is ClosedGroupControlMessage -> handleClosedGroupControlMessage(message)
        is ExpirationTimerUpdate -> handleExpirationTimerUpdate(message)
        is DataExtractionNotification -> handleDataExtractionNotification(message)
        is ConfigurationMessage -> handleConfigurationMessage(message)
        is UnsendRequest -> handleUnsendRequest(message)
        is MessageRequestResponse -> handleMessageRequestResponse(message)
        is VisibleMessage -> handleVisibleMessage(message, proto, openGroupID,
            runIncrement = true,
            runThreadUpdate = true,
            runProfileUpdate = true
        )
        is CallMessage -> handleCallMessage(message)
    }
}

// region Control Messages
private fun MessageReceiver.handleReadReceipt(message: ReadReceipt) {
    val context = MessagingModuleConfiguration.shared.context
    SSKEnvironment.shared.readReceiptManager.processReadReceipts(context, message.sender!!, message.timestamps!!, message.receivedTimestamp!!)
}

private fun MessageReceiver.handleCallMessage(message: CallMessage) {
    // TODO: refactor this out to persistence, just to help debug the flow and send/receive in synchronous testing
    WebRtcUtils.SIGNAL_QUEUE.trySend(message)
}

private fun MessageReceiver.handleTypingIndicator(message: TypingIndicator) {
    when (message.kind!!) {
        TypingIndicator.Kind.STARTED -> showTypingIndicatorIfNeeded(message.sender!!)
        TypingIndicator.Kind.STOPPED -> hideTypingIndicatorIfNeeded(message.sender!!)
    }
}

fun MessageReceiver.showTypingIndicatorIfNeeded(senderPublicKey: String) {
    val context = MessagingModuleConfiguration.shared.context
    val address = Address.fromSerialized(senderPublicKey)
    val threadID = MessagingModuleConfiguration.shared.storage.getThreadId(address) ?: return
    SSKEnvironment.shared.typingIndicators.didReceiveTypingStartedMessage(context, threadID, address, 1)
}

fun MessageReceiver.hideTypingIndicatorIfNeeded(senderPublicKey: String) {
    val context = MessagingModuleConfiguration.shared.context
    val address = Address.fromSerialized(senderPublicKey)
    val threadID = MessagingModuleConfiguration.shared.storage.getThreadId(address) ?: return
    SSKEnvironment.shared.typingIndicators.didReceiveTypingStoppedMessage(context, threadID, address, 1, false)
}

fun MessageReceiver.cancelTypingIndicatorsIfNeeded(senderPublicKey: String) {
    val context = MessagingModuleConfiguration.shared.context
    val address = Address.fromSerialized(senderPublicKey)
    val threadID = MessagingModuleConfiguration.shared.storage.getThreadId(address) ?: return
    SSKEnvironment.shared.typingIndicators.didReceiveIncomingMessage(context, threadID, address, 1)
}

private fun MessageReceiver.handleExpirationTimerUpdate(message: ExpirationTimerUpdate) {
    if (message.duration!! > 0) {
        SSKEnvironment.shared.messageExpirationManager.setExpirationTimer(message)
    } else {
        SSKEnvironment.shared.messageExpirationManager.disableExpirationTimer(message)
    }
}

private fun MessageReceiver.handleDataExtractionNotification(message: DataExtractionNotification) {
    // We don't handle data extraction messages for groups (they shouldn't be sent, but just in case we filter them here too)
    if (message.groupPublicKey != null) return
    val storage = MessagingModuleConfiguration.shared.storage
    val senderPublicKey = message.sender!!
    val notification: DataExtractionNotificationInfoMessage = when(message.kind) {
        is DataExtractionNotification.Kind.Screenshot -> DataExtractionNotificationInfoMessage(DataExtractionNotificationInfoMessage.Kind.SCREENSHOT)
        is DataExtractionNotification.Kind.MediaSaved -> DataExtractionNotificationInfoMessage(DataExtractionNotificationInfoMessage.Kind.MEDIA_SAVED)
        else -> return
    }
    storage.insertDataExtractionNotificationMessage(senderPublicKey, notification, message.sentTimestamp!!)
}

private fun handleConfigurationMessage(message: ConfigurationMessage) {
    val context = MessagingModuleConfiguration.shared.context
    val storage = MessagingModuleConfiguration.shared.storage
    if (TextSecurePreferences.getConfigurationMessageSynced(context)
        && !TextSecurePreferences.shouldUpdateProfile(context, message.sentTimestamp!!)) return
    val userPublicKey = storage.getUserPublicKey()
    if (userPublicKey == null || message.sender != storage.getUserPublicKey()) return

    val firstTimeSync = !TextSecurePreferences.getConfigurationMessageSynced(context)

    TextSecurePreferences.setConfigurationMessageSynced(context, true)
    TextSecurePreferences.setLastProfileUpdateTime(context, message.sentTimestamp!!)
    val allClosedGroupPublicKeys = storage.getAllClosedGroupPublicKeys()
    for (closedGroup in message.closedGroups) {
        if (allClosedGroupPublicKeys.contains(closedGroup.publicKey)) {
            // just handle the closed group encryption key pairs to avoid sync'd devices getting out of sync
            storage.addClosedGroupEncryptionKeyPair(closedGroup.encryptionKeyPair!!, closedGroup.publicKey)
        } else if (firstTimeSync) {
            // only handle new closed group if it's first time sync
            handleNewClosedGroup(message.sender!!, message.sentTimestamp!!, closedGroup.publicKey, closedGroup.name,
                closedGroup.encryptionKeyPair!!, closedGroup.members, closedGroup.admins, message.sentTimestamp!!, closedGroup.expirationTimer)
        }
    }
    val allV2OpenGroups = storage.getAllOpenGroups().map { it.value.joinURL }
    for (openGroup in message.openGroups.map {
        it.replace(OpenGroupApi.legacyDefaultServer, OpenGroupApi.defaultServer)
            .replace(OpenGroupApi.httpDefaultServer, OpenGroupApi.defaultServer)
    }) {
        if (allV2OpenGroups.contains(openGroup)) continue
        Log.d("OpenGroup", "All open groups doesn't contain $openGroup")
        if (!storage.hasBackgroundGroupAddJob(openGroup)) {
            Log.d("OpenGroup", "Doesn't contain background job for $openGroup, adding")
            JobQueue.shared.add(BackgroundGroupAddJob(openGroup))
        }
    }
    val profileManager = SSKEnvironment.shared.profileManager
    val recipient = Recipient.from(context, Address.fromSerialized(userPublicKey), false)
    if (message.displayName.isNotEmpty()) {
        TextSecurePreferences.setProfileName(context, message.displayName)
        profileManager.setName(context, recipient, message.displayName)
    }
    if (message.profileKey.isNotEmpty() && !message.profilePicture.isNullOrEmpty()
        && TextSecurePreferences.getProfilePictureURL(context) != message.profilePicture) {
        val profileKey = Base64.encodeBytes(message.profileKey)
        ProfileKeyUtil.setEncodedProfileKey(context, profileKey)
        profileManager.setProfileKey(context, recipient, message.profileKey)
        if (!message.profilePicture.isNullOrEmpty() && TextSecurePreferences.getProfilePictureURL(context) != message.profilePicture) {
            storage.setUserProfilePictureURL(message.profilePicture!!)
        }
    }
    storage.addContacts(message.contacts)
}

fun MessageReceiver.handleUnsendRequest(message: UnsendRequest) {
    val userPublicKey = MessagingModuleConfiguration.shared.storage.getUserPublicKey()
    if (message.sender != message.author && (message.sender != userPublicKey && userPublicKey != null)) { return }
    val context = MessagingModuleConfiguration.shared.context
    val storage = MessagingModuleConfiguration.shared.storage
    val messageDataProvider = MessagingModuleConfiguration.shared.messageDataProvider
    val timestamp = message.timestamp ?: return
    val author = message.author ?: return
    val messageIdToDelete = storage.getMessageIdInDatabase(timestamp, author) ?: return
    messageDataProvider.getServerHashForMessage(messageIdToDelete)?.let { serverHash ->
        SnodeAPI.deleteMessage(author, listOf(serverHash))
    }
    messageDataProvider.updateMessageAsDeleted(timestamp, author)
    if (!messageDataProvider.isOutgoingMessage(messageIdToDelete)) {
        SSKEnvironment.shared.notificationManager.updateNotification(context)
    }
}

fun handleMessageRequestResponse(message: MessageRequestResponse) {
    MessagingModuleConfiguration.shared.storage.insertMessageRequestResponse(message)
}
//endregion

fun MessageReceiver.handleVisibleMessage(message: VisibleMessage,
                                         proto: SignalServiceProtos.Content,
                                         openGroupID: String?,
                                         runIncrement: Boolean,
                                         runThreadUpdate: Boolean,
                                         runProfileUpdate: Boolean): Long? {
    val storage = MessagingModuleConfiguration.shared.storage
    val context = MessagingModuleConfiguration.shared.context
    val userPublicKey = storage.getUserPublicKey()
    val messageSender: String? = message.sender
    // Get or create thread
    // FIXME: In case this is an open group this actually * doesn't * create the thread if it doesn't yet
    //        exist. This is intentional, but it's very non-obvious.
    val threadID = storage.getOrCreateThreadIdFor(message.syncTarget ?: messageSender!!, message.groupPublicKey, openGroupID)
    if (threadID < 0) {
        // Thread doesn't exist; should only be reached in a case where we are processing open group messages for a no longer existent thread
        throw MessageReceiver.Error.NoThread
    }
    val threadRecipient = storage.getRecipientForThread(threadID)
    val userBlindedKey = openGroupID?.let {
        val openGroup = storage.getOpenGroup(threadID) ?: return@let null
        val blindedKey = SodiumUtilities.blindedKeyPair(openGroup.publicKey, MessagingModuleConfiguration.shared.getUserED25519KeyPair()!!) ?: return@let null
        SessionId(
            IdPrefix.BLINDED, blindedKey.publicKey.asBytes
        ).hexString
    }
    // Update profile if needed
    val recipient = Recipient.from(context, Address.fromSerialized(messageSender!!), false)
    if (runProfileUpdate) {
        val profile = message.profile
        val isUserBlindedSender = messageSender == userBlindedKey
        if (profile != null && userPublicKey != messageSender && !isUserBlindedSender) {
            val profileManager = SSKEnvironment.shared.profileManager
            val name = profile.displayName!!
            if (name.isNotEmpty()) {
                profileManager.setName(context, recipient, name)
            }
            val newProfileKey = profile.profileKey

            val needsProfilePicture = !AvatarHelper.avatarFileExists(context, Address.fromSerialized(messageSender))
            val profileKeyValid = newProfileKey?.isNotEmpty() == true && (newProfileKey.size == 16 || newProfileKey.size == 32) && profile.profilePictureURL?.isNotEmpty() == true
            val profileKeyChanged = (recipient.profileKey == null || !MessageDigest.isEqual(recipient.profileKey, newProfileKey))

            if ((profileKeyValid && profileKeyChanged) || (profileKeyValid && needsProfilePicture)) {
                profileManager.setProfileKey(context, recipient, newProfileKey!!)
                profileManager.setUnidentifiedAccessMode(context, recipient, Recipient.UnidentifiedAccessMode.UNKNOWN)
                profileManager.setProfilePictureURL(context, recipient, profile.profilePictureURL!!)
            }
        }
    }
    // Parse quote if needed
    var quoteModel: QuoteModel? = null
    if (message.quote != null && proto.dataMessage.hasQuote()) {
        val quote = proto.dataMessage.quote

        val author = if (quote.author == userBlindedKey) {
            Address.fromSerialized(userPublicKey!!)
        } else {
            Address.fromSerialized(quote.author)
        }

        val messageDataProvider = MessagingModuleConfiguration.shared.messageDataProvider
        val messageInfo = messageDataProvider.getMessageForQuote(quote.id, author)
        quoteModel = if (messageInfo != null) {
            val attachments = if (messageInfo.second) messageDataProvider.getAttachmentsAndLinkPreviewFor(messageInfo.first) else ArrayList()
            QuoteModel(quote.id, author,null,false, attachments)
        } else {
            QuoteModel(quote.id, author,null, true, PointerAttachment.forPointers(proto.dataMessage.quote.attachmentsList))
        }
    }
    // Parse link preview if needed
    val linkPreviews: MutableList<LinkPreview?> = mutableListOf()
    if (message.linkPreview != null && proto.dataMessage.previewCount > 0) {
        for (preview in proto.dataMessage.previewList) {
            val thumbnail = PointerAttachment.forPointer(preview.image)
            val url = Optional.fromNullable(preview.url)
            val title = Optional.fromNullable(preview.title)
            val hasContent = !TextUtils.isEmpty(title.or("")) || thumbnail.isPresent
            if (hasContent) {
                val linkPreview = LinkPreview(url.get(), title.or(""), thumbnail)
                linkPreviews.add(linkPreview)
            } else {
                Log.w("Loki", "Discarding an invalid link preview. hasContent: $hasContent")
            }
        }
    }
    // Parse attachments if needed
    val attachments = proto.dataMessage.attachmentsList.mapNotNull { attachmentProto ->
        val attachment = Attachment.fromProto(attachmentProto)
        if (!attachment.isValid()) {
            return@mapNotNull null
        } else {
            return@mapNotNull attachment
        }
    }
    // Cancel any typing indicators if needed
    cancelTypingIndicatorsIfNeeded(message.sender!!)
    // Parse reaction if needed
    val threadIsGroup = threadRecipient?.isGroupRecipient == true
    message.reaction?.let { reaction ->
        if (reaction.react == true) {
            reaction.serverId = message.openGroupServerMessageID?.toString() ?: message.serverHash.orEmpty()
            reaction.dateSent = message.sentTimestamp ?: 0
            reaction.dateReceived = message.receivedTimestamp ?: 0
            storage.addReaction(reaction, messageSender, !threadIsGroup)
        } else {
            storage.removeReaction(reaction.emoji!!, reaction.timestamp!!, reaction.publicKey!!, threadIsGroup)
        }
    } ?: run {
        // Persist the message
        message.threadID = threadID
        val messageID =
            storage.persist(message, quoteModel, linkPreviews, message.groupPublicKey, openGroupID,
         attachments, runIncrement, runThreadUpdate
        ) ?: return null
        val openGroupServerID = message.openGroupServerMessageID
        if (openGroupServerID != null) {
            val isSms = !(message.isMediaMessage() || attachments.isNotEmpty())
            storage.setOpenGroupServerMessageID(messageID, openGroupServerID, threadID, isSms)
        }
        return messageID
    }
    return null
}

fun MessageReceiver.handleOpenGroupReactions(
    threadId: Long,
    openGroupMessageServerID: Long,
    reactions: Map<String, OpenGroupApi.Reaction>?
) {
    if (reactions.isNullOrEmpty()) return
    val storage = MessagingModuleConfiguration.shared.storage
    val (messageId, isSms) = MessagingModuleConfiguration.shared.messageDataProvider.getMessageID(openGroupMessageServerID, threadId) ?: return
    storage.deleteReactions(messageId, !isSms)
    val userPublicKey = storage.getUserPublicKey()!!
    val openGroup = storage.getOpenGroup(threadId)
    val blindedPublicKey = openGroup?.publicKey?.let { serverPublicKey ->
        SodiumUtilities.blindedKeyPair(serverPublicKey, MessagingModuleConfiguration.shared.getUserED25519KeyPair()!!)
            ?.let { SessionId(IdPrefix.BLINDED, it.publicKey.asBytes).hexString }
    }
    for ((emoji, reaction) in reactions) {
        val pendingUserReaction = OpenGroupApi.pendingReactions
            .filter { it.server == openGroup?.server && it.room == openGroup.room && it.messageId == openGroupMessageServerID && it.add }
            .sortedByDescending { it.seqNo }
            .any { it.emoji == emoji }
        val shouldAddUserReaction = pendingUserReaction || reaction.you || reaction.reactors.contains(userPublicKey)
        val reactorIds = reaction.reactors.filter { it != blindedPublicKey && it != userPublicKey }
        val count = if (reaction.you) reaction.count - 1 else reaction.count
        // Add the first reaction (with the count)
        reactorIds.firstOrNull()?.let { reactor ->
            storage.addReaction(Reaction(
                localId = messageId,
                isMms = !isSms,
                publicKey = reactor,
                emoji = emoji,
                react = true,
                serverId = "$openGroupMessageServerID",
                count = count,
                index = reaction.index
            ), reactor, false)
        }

        // Add all other reactions
        val maxAllowed = if (shouldAddUserReaction) 4 else 5
        val lastIndex = min(maxAllowed, reactorIds.size)
        reactorIds.slice(1 until lastIndex).map { reactor ->
            storage.addReaction(Reaction(
                localId = messageId,
                isMms = !isSms,
                publicKey = reactor,
                emoji = emoji,
                react = true,
                serverId = "$openGroupMessageServerID",
                count = 0,  // Only want this on the first reaction
                index = reaction.index
            ), reactor, false)
        }

        // Add the current user reaction (if applicable and not already included)
        if (shouldAddUserReaction) {
            storage.addReaction(Reaction(
                localId = messageId,
                isMms = !isSms,
                publicKey = userPublicKey,
                emoji = emoji,
                react = true,
                serverId = "$openGroupMessageServerID",
                count = 1,
                index = reaction.index
            ), userPublicKey, false)
        }
    }
}

//endregion

// region Closed Groups
private fun MessageReceiver.handleClosedGroupControlMessage(message: ClosedGroupControlMessage) {
    when (message.kind!!) {
        is ClosedGroupControlMessage.Kind.New -> handleNewClosedGroup(message)
        is ClosedGroupControlMessage.Kind.EncryptionKeyPair -> handleClosedGroupEncryptionKeyPair(message)
        is ClosedGroupControlMessage.Kind.NameChange -> handleClosedGroupNameChanged(message)
        is ClosedGroupControlMessage.Kind.MembersAdded -> handleClosedGroupMembersAdded(message)
        is ClosedGroupControlMessage.Kind.MembersRemoved -> handleClosedGroupMembersRemoved(message)
        is ClosedGroupControlMessage.Kind.MemberLeft -> handleClosedGroupMemberLeft(message)
    }
}

private fun MessageReceiver.handleNewClosedGroup(message: ClosedGroupControlMessage) {
    val kind = message.kind!! as? ClosedGroupControlMessage.Kind.New ?: return
    val recipient = Recipient.from(MessagingModuleConfiguration.shared.context, Address.fromSerialized(message.sender!!), false)
    if (!recipient.isApproved) return
    val groupPublicKey = kind.publicKey.toByteArray().toHexString()
    val members = kind.members.map { it.toByteArray().toHexString() }
    val admins = kind.admins.map { it.toByteArray().toHexString() }
    val expireTimer = kind.expirationTimer
    handleNewClosedGroup(message.sender!!, message.sentTimestamp!!, groupPublicKey, kind.name, kind.encryptionKeyPair!!, members, admins, message.sentTimestamp!!, expireTimer)
}

private fun handleNewClosedGroup(sender: String, sentTimestamp: Long, groupPublicKey: String, name: String, encryptionKeyPair: ECKeyPair, members: List<String>, admins: List<String>, formationTimestamp: Long, expireTimer: Int) {
    val context = MessagingModuleConfiguration.shared.context
    val storage = MessagingModuleConfiguration.shared.storage
    val userPublicKey = TextSecurePreferences.getLocalNumber(context)
    // Create the group
    val groupID = GroupUtil.doubleEncodeGroupID(groupPublicKey)
    val groupExists = storage.getGroup(groupID) != null
    if (groupExists) {
        // Update the group
        if (!storage.isGroupActive(groupPublicKey)) {
            // Clear zombie list if the group wasn't active
            storage.setZombieMembers(groupID, listOf())
            // Update the formation timestamp
            storage.updateFormationTimestamp(groupID, formationTimestamp)
        }
        storage.updateTitle(groupID, name)
        storage.updateMembers(groupID, members.map { Address.fromSerialized(it) })
    } else {
        storage.createGroup(groupID, name, LinkedList(members.map { Address.fromSerialized(it) }),
            null, null, LinkedList(admins.map { Address.fromSerialized(it) }), formationTimestamp)
    }
    storage.setProfileSharing(Address.fromSerialized(groupID), true)
    // Add the group to the user's set of public keys to poll for
    storage.addClosedGroupPublicKey(groupPublicKey)
    // Store the encryption key pair
    storage.addClosedGroupEncryptionKeyPair(encryptionKeyPair, groupPublicKey)
    // Set expiration timer
    storage.setExpirationTimer(groupID, expireTimer)
    // Notify the PN server
    PushNotificationAPI.performOperation(PushNotificationAPI.ClosedGroupOperation.Subscribe, groupPublicKey, storage.getUserPublicKey()!!)
    // Notify the user
    if (userPublicKey == sender && !groupExists) {
        val threadID = storage.getOrCreateThreadIdFor(Address.fromSerialized(groupID))
        storage.insertOutgoingInfoMessage(context, groupID, SignalServiceGroup.Type.CREATION, name, members, admins, threadID, sentTimestamp)
    } else if (userPublicKey != sender) {
        storage.insertIncomingInfoMessage(context, sender, groupID, SignalServiceGroup.Type.CREATION, name, members, admins, sentTimestamp)
    }
    // Start polling
    ClosedGroupPollerV2.shared.startPolling(groupPublicKey)
}

private fun MessageReceiver.handleClosedGroupEncryptionKeyPair(message: ClosedGroupControlMessage) {
    // Prepare
    val storage = MessagingModuleConfiguration.shared.storage
    val senderPublicKey = message.sender ?: return
    val kind = message.kind!! as? ClosedGroupControlMessage.Kind.EncryptionKeyPair ?: return
    var groupPublicKey = kind.publicKey?.toByteArray()?.toHexString()
    if (groupPublicKey.isNullOrEmpty()) groupPublicKey = message.groupPublicKey ?: return
    val userPublicKey = storage.getUserPublicKey()!!
    val userKeyPair = storage.getUserX25519KeyPair()
    // Unwrap the message
    val groupID = GroupUtil.doubleEncodeGroupID(groupPublicKey)
    val group = storage.getGroup(groupID) ?: run {
        Log.d("Loki", "Ignoring closed group encryption key pair for nonexistent group.")
        return
    }
    if (!group.isActive) {
        Log.d("Loki", "Ignoring closed group encryption key pair for inactive group.")
        return
    }
    if (!group.admins.map { it.toString() }.contains(senderPublicKey)) {
        Log.d("Loki", "Ignoring closed group encryption key pair from non-admin.")
        return
    }
    // Find our wrapper and decrypt it if possible
    val wrapper = kind.wrappers.firstOrNull { it.publicKey!! == userPublicKey } ?: return
    val encryptedKeyPair = wrapper.encryptedKeyPair!!.toByteArray()
    val plaintext = MessageDecrypter.decrypt(encryptedKeyPair, userKeyPair).first
    // Parse it
    val proto = SignalServiceProtos.KeyPair.parseFrom(plaintext)
    val keyPair = ECKeyPair(DjbECPublicKey(proto.publicKey.toByteArray().removingIdPrefixIfNeeded()), DjbECPrivateKey(proto.privateKey.toByteArray()))
    // Store it if needed
    val closedGroupEncryptionKeyPairs = storage.getClosedGroupEncryptionKeyPairs(groupPublicKey)
    if (closedGroupEncryptionKeyPairs.contains(keyPair)) {
        Log.d("Loki", "Ignoring duplicate closed group encryption key pair.")
        return
    }
    storage.addClosedGroupEncryptionKeyPair(keyPair, groupPublicKey)
    Log.d("Loki", "Received a new closed group encryption key pair.")
}

private fun MessageReceiver.handleClosedGroupNameChanged(message: ClosedGroupControlMessage) {
    val context = MessagingModuleConfiguration.shared.context
    val storage = MessagingModuleConfiguration.shared.storage
    val userPublicKey = TextSecurePreferences.getLocalNumber(context)
    val senderPublicKey = message.sender ?: return
    val kind = message.kind!! as? ClosedGroupControlMessage.Kind.NameChange ?: return
    val groupPublicKey = message.groupPublicKey ?: return
    // Check that the sender is a member of the group (before the update)
    val groupID = GroupUtil.doubleEncodeGroupID(groupPublicKey)
    val group = storage.getGroup(groupID) ?: run {
        Log.d("Loki", "Ignoring closed group update for nonexistent group.")
        return
    }
    if (!group.isActive) {
        Log.d("Loki", "Ignoring closed group update for inactive group.")
        return
    }
    // Check common group update logic
    if (!isValidGroupUpdate(group, message.sentTimestamp!!, senderPublicKey)) {
        return
    }
    val members = group.members.map { it.serialize() }
    val admins = group.admins.map { it.serialize() }
    val name = kind.name
    storage.updateTitle(groupID, name)
    // Notify the user
    if (userPublicKey == senderPublicKey) {
        val threadID = storage.getOrCreateThreadIdFor(Address.fromSerialized(groupID))
        storage.insertOutgoingInfoMessage(context, groupID, SignalServiceGroup.Type.NAME_CHANGE, name, members, admins, threadID, message.sentTimestamp!!)
    } else {
        storage.insertIncomingInfoMessage(context, senderPublicKey, groupID, SignalServiceGroup.Type.NAME_CHANGE, name, members, admins, message.sentTimestamp!!)
    }
}

private fun MessageReceiver.handleClosedGroupMembersAdded(message: ClosedGroupControlMessage) {
    val context = MessagingModuleConfiguration.shared.context
    val storage = MessagingModuleConfiguration.shared.storage
    val userPublicKey = storage.getUserPublicKey()!!
    val senderPublicKey = message.sender ?: return
    val kind = message.kind!! as? ClosedGroupControlMessage.Kind.MembersAdded ?: return
    val groupPublicKey = message.groupPublicKey ?: return
    val groupID = GroupUtil.doubleEncodeGroupID(groupPublicKey)
    val group = storage.getGroup(groupID) ?: run {
        Log.d("Loki", "Ignoring closed group update for nonexistent group.")
        return
    }
    if (!group.isActive) {
        Log.d("Loki", "Ignoring closed group update for inactive group.")
        return
    }
    if (!isValidGroupUpdate(group, message.sentTimestamp!!, senderPublicKey)) { return }
    val name = group.title
    // Check common group update logic
    val members = group.members.map { it.serialize() }
    val admins = group.admins.map { it.serialize() }

    val updateMembers = kind.members.map { it.toByteArray().toHexString() }
    val newMembers = members + updateMembers
    storage.updateMembers(groupID, newMembers.map { Address.fromSerialized(it) })

    // Update zombie members in case the added members are zombies
    val zombies = storage.getZombieMembers(groupID)
    if (zombies.intersect(updateMembers).isNotEmpty()) {
        storage.setZombieMembers(groupID, zombies.minus(updateMembers).map { Address.fromSerialized(it) })
    }

    // Notify the user
    if (userPublicKey == senderPublicKey) {
        val threadID = storage.getOrCreateThreadIdFor(Address.fromSerialized(groupID))
        storage.insertOutgoingInfoMessage(context, groupID, SignalServiceGroup.Type.MEMBER_ADDED, name, updateMembers, admins, threadID, message.sentTimestamp!!)
    } else {
        storage.insertIncomingInfoMessage(context, senderPublicKey, groupID, SignalServiceGroup.Type.MEMBER_ADDED, name, updateMembers, admins, message.sentTimestamp!!)
    }
    if (userPublicKey in admins) {
        // Send the latest encryption key pair to the added members if the current user is the admin of the group
        //
        // This fixes a race condition where:
        // • A member removes another member.
        // • A member adds someone to the group and sends them the latest group key pair.
        // • The admin is offline during all of this.
        // • When the admin comes back online they see the member removed message and generate + distribute a new key pair,
        //   but they don't know about the added member yet.
        // • Now they see the member added message.
        //
        // Without the code below, the added member(s) would never get the key pair that was generated by the admin when they saw
        // the member removed message.
        val encryptionKeyPair = pendingKeyPairs[groupPublicKey]?.orNull()
            ?: storage.getLatestClosedGroupEncryptionKeyPair(groupPublicKey)
        if (encryptionKeyPair == null) {
            Log.d("Loki", "Couldn't get encryption key pair for closed group.")
        } else {
            for (user in updateMembers) {
                MessageSender.sendEncryptionKeyPair(groupPublicKey, encryptionKeyPair, setOf(user), targetUser = user, force = false)
            }
        }
    }
}

/// Removes the given members from the group IF
/// • it wasn't the admin that was removed (that should happen through a `MEMBER_LEFT` message).
/// • the admin sent the message (only the admin can truly remove members).
/// If we're among the users that were removed, delete all encryption key pairs and the group public key, unsubscribe
/// from push notifications for this closed group, and remove the given members from the zombie list for this group.
private fun MessageReceiver.handleClosedGroupMembersRemoved(message: ClosedGroupControlMessage) {
    val context = MessagingModuleConfiguration.shared.context
    val storage = MessagingModuleConfiguration.shared.storage
    val userPublicKey = storage.getUserPublicKey()!!
    val senderPublicKey = message.sender ?: return
    val kind = message.kind!! as? ClosedGroupControlMessage.Kind.MembersRemoved ?: return
    val groupPublicKey = message.groupPublicKey ?: return
    val groupID = GroupUtil.doubleEncodeGroupID(groupPublicKey)
    val group = storage.getGroup(groupID) ?: run {
        Log.d("Loki", "Ignoring closed group update for nonexistent group.")
        return
    }
    if (!group.isActive) {
        Log.d("Loki", "Ignoring closed group update for inactive group.")
        return
    }
    val name = group.title
    // Check common group update logic
    val members = group.members.map { it.serialize() }
    val admins = group.admins.map { it.toString() }
    val removedMembers = kind.members.map { it.toByteArray().toHexString() }
    val zombies: Set<String> = storage.getZombieMembers(groupID)
    // Check that the admin wasn't removed
    if (removedMembers.contains(admins.first())) {
        Log.d("Loki", "Ignoring invalid closed group update.")
        return
    }
    // Check that the message was sent by the group admin
    if (!admins.contains(senderPublicKey)) {
        Log.d("Loki", "Ignoring invalid closed group update.")
        return
    }
    if (!isValidGroupUpdate(group, message.sentTimestamp!!, senderPublicKey)) { return }
    // If the admin leaves the group is disbanded
    val didAdminLeave = admins.any { it in removedMembers }
    val newMembers = members - removedMembers
    // A user should be posting a MEMBERS_LEFT in case they leave, so this shouldn't be encountered
    val senderLeft = senderPublicKey in removedMembers
    if (senderLeft) {
        Log.d("Loki", "Received a MEMBERS_REMOVED instead of a MEMBERS_LEFT from sender: $senderPublicKey.")
    }
    val wasCurrentUserRemoved = userPublicKey in removedMembers
    // Admin should send a MEMBERS_LEFT message but handled here just in case
    if (didAdminLeave || wasCurrentUserRemoved) {
        disableLocalGroupAndUnsubscribe(groupPublicKey, groupID, userPublicKey)
    } else {
        storage.updateMembers(groupID, newMembers.map { Address.fromSerialized(it) })
        // Update zombie members
        storage.setZombieMembers(groupID, zombies.minus(removedMembers).map { Address.fromSerialized(it) })
    }

    // Notify the user
    val type = if (senderLeft) SignalServiceGroup.Type.QUIT else SignalServiceGroup.Type.MEMBER_REMOVED
    // We don't display zombie members in the notification as users have already been notified when those members left
    val notificationMembers = removedMembers.minus(zombies)
    if (notificationMembers.isNotEmpty()) {
        // No notification to display when only zombies have been removed
        if (userPublicKey == senderPublicKey) {
            val threadID = storage.getOrCreateThreadIdFor(Address.fromSerialized(groupID))
            storage.insertOutgoingInfoMessage(context, groupID, type, name, notificationMembers, admins, threadID, message.sentTimestamp!!)
        } else {
            storage.insertIncomingInfoMessage(context, senderPublicKey, groupID, type, name, notificationMembers, admins, message.sentTimestamp!!)
        }
    }
}

/// If a regular member left:
/// • Mark them as a zombie (to be removed by the admin later).
/// If the admin left:
/// • Unsubscribe from PNs, delete the group public key, etc. as the group will be disbanded.
private fun MessageReceiver.handleClosedGroupMemberLeft(message: ClosedGroupControlMessage) {
    val context = MessagingModuleConfiguration.shared.context
    val storage = MessagingModuleConfiguration.shared.storage
    val senderPublicKey = message.sender ?: return
    val userPublicKey = storage.getUserPublicKey()!!
    if (message.kind!! !is ClosedGroupControlMessage.Kind.MemberLeft) return
    val groupPublicKey = message.groupPublicKey ?: return
    val groupID = GroupUtil.doubleEncodeGroupID(groupPublicKey)
    val group = storage.getGroup(groupID) ?: run {
        Log.d("Loki", "Ignoring closed group update for nonexistent group.")
        return
    }
    if (!group.isActive) {
        Log.d("Loki", "Ignoring closed group update for inactive group.")
        return
    }
    val name = group.title
    // Check common group update logic
    val members = group.members.map { it.serialize() }
    val admins = group.admins.map { it.toString() }
    if (!isValidGroupUpdate(group, message.sentTimestamp!!, senderPublicKey)) {
        return
    }
    // If admin leaves the group is disbanded
    val didAdminLeave = admins.contains(senderPublicKey)
    val updatedMemberList = members - senderPublicKey
    val userLeft = (userPublicKey == senderPublicKey)
    if (didAdminLeave || userLeft) {
        disableLocalGroupAndUnsubscribe(groupPublicKey, groupID, userPublicKey)
    } else {
        storage.updateMembers(groupID, updatedMemberList.map { Address.fromSerialized(it) })
        // Update zombie members
        val zombies = storage.getZombieMembers(groupID)
        storage.setZombieMembers(groupID, zombies.plus(senderPublicKey).map { Address.fromSerialized(it) })
    }
    // Notify the user
    if (userLeft) {
        val threadID = storage.getOrCreateThreadIdFor(Address.fromSerialized(groupID))
        storage.insertOutgoingInfoMessage(context, groupID, SignalServiceGroup.Type.QUIT, name, members, admins, threadID, message.sentTimestamp!!)
    } else {
        storage.insertIncomingInfoMessage(context, senderPublicKey, groupID, SignalServiceGroup.Type.QUIT, name, members, admins, message.sentTimestamp!!)
    }
}

private fun isValidGroupUpdate(group: GroupRecord, sentTimestamp: Long, senderPublicKey: String): Boolean  {
    val oldMembers = group.members.map { it.serialize() }
    // Check that the message isn't from before the group was created
    if (group.formationTimestamp > sentTimestamp) {
        Log.d("Loki", "Ignoring closed group update from before thread was created.")
        return false
    }
    // Check that the sender is a member of the group (before the update)
    if (senderPublicKey !in oldMembers) {
        Log.d("Loki", "Ignoring closed group info message from non-member.")
        return false
    }
    return true
}

fun MessageReceiver.disableLocalGroupAndUnsubscribe(groupPublicKey: String, groupID: String, userPublicKey: String) {
    val storage = MessagingModuleConfiguration.shared.storage
    storage.removeClosedGroupPublicKey(groupPublicKey)
    // Remove the key pairs
    storage.removeAllClosedGroupEncryptionKeyPairs(groupPublicKey)
    // Mark the group as inactive
    storage.setActive(groupID, false)
    storage.removeMember(groupID, Address.fromSerialized(userPublicKey))
    // Notify the PN server
    PushNotificationAPI.performOperation(PushNotificationAPI.ClosedGroupOperation.Unsubscribe, groupPublicKey, userPublicKey)
    // Stop polling
    ClosedGroupPollerV2.shared.stopPolling(groupPublicKey)
}
// endregion
