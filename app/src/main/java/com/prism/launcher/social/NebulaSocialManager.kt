package com.prism.launcher.social

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import com.prism.launcher.AppDatabase
import com.prism.launcher.PrismSettings
import com.prism.launcher.messaging.AiManager
import com.prism.launcher.messaging.ImageGenManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.*

object NebulaSocialManager {

    /**
     * Entry point for generating new AI content.
     * @param manual If true, ignores the Idle/Charging check (user-initiated).
     */
    suspend fun generateNewContent(context: Context, manual: Boolean = false) = withContext(Dispatchers.IO) {
        val mode = PrismSettings.getAiMode(context)
        val isCloud = mode == PrismSettings.AI_MODE_CLOUD
        
        // Check constraints for automatic background generation
        if (!manual) {
            val isCharging = isDeviceCharging(context)
            val isIdle = isDeviceIdle(context)
            
            if (isCloud) {
                // Cloud can generate in background always, or idle/charging
                // No strict blockade
            } else {
                // Local MUST be idle + charging
                if (!isCharging || !isIdle) return@withContext
            }
        }

        // 1. Ensure we have some Bots
        ensureBotsExist(context)

        // 2. Select a random bot to post
        val db = AppDatabase.get(context)
        val bots = db.socialDao().getAllBots()
        if (bots.isEmpty()) return@withContext
        
        val bot = bots.random()
        
        // 3. Generate content via AiManager
        val prompt = "You are ${bot.name} (@${bot.handle}), a persona who is ${bot.personaType}. " +
                     "Write a short, realistic social media post for the Nebula mesh. Keep it under 200 characters. " +
                     "After the post text, include the tag [VISUAL] followed by a highly descriptive prompt for a realistic image representing this post."
        
        val (responseText, attachment) = AiManager.getResponse(context, prompt)
        
        // 4. Handle Visual Generation
        val visualPrompt = ImageGenManager.extractVisualPrompt(responseText)
        val finalContent = responseText.substringBefore("[VISUAL]").trim()
        
        var finalImageUrl = attachment.first
        if (visualPrompt != null) {
            val generatedUri = ImageGenManager.generateImage(context, visualPrompt)
            if (generatedUri != null) {
                finalImageUrl = generatedUri.toString()
            }
        }

        // 5. Persistence
        val post = SocialPostEntity(
            postId = UUID.randomUUID().toString(),
            authorId = bot.botId,
            authorName = bot.name,
            authorHandle = bot.handle,
            authorAvatarUrl = bot.avatarUrl,
            content = finalContent,
            imageUrl = finalImageUrl,
            likesCount = (10..500).random(),
            repostCount = (1..50).random()
        )
        
        db.socialDao().insertPost(post)

        // Occasionally generate random interaction (Comment or DM)
        if (Math.random() < 0.3) {
            val otherPost = db.socialDao().getAllPosts().firstOrNull()
            if (otherPost != null) generateBotComment(context, bot, otherPost)
        }
        if (Math.random() < 0.1) {
            generateBotDM(context, bot)
        }
    }

    suspend fun generateBotComment(context: Context, bot: SocialBotEntity, post: SocialPostEntity, visionTags: String = "") = withContext(Dispatchers.IO) {
        val db = AppDatabase.get(context)
        
        val contextText = if (visionTags.isNotEmpty()) "The user posted an image. It contains: $visionTags. " else ""
        val prompt = "You are ${bot.name} (@${bot.handle}). " +
                     "Comment on this post: '${post.content}'. $contextText" +
                     "Keep it short, direct, and conversational."
        
        val (commentText, _) = AiManager.getResponse(context, prompt)
        val comment = SocialCommentEntity(
            postId = post.postId,
            authorId = bot.botId,
            authorName = bot.name,
            authorHandle = bot.handle,
            authorAvatarUrl = bot.avatarUrl,
            content = commentText
        )
        db.socialDao().insertComment(comment)
    }

    suspend fun generateBotDM(context: Context, bot: SocialBotEntity) = withContext(Dispatchers.IO) {
        val db = AppDatabase.get(context)
        val prompt = "You are ${bot.name} (@${bot.handle}). " +
                     "Send a friendly direct message to the user to start a conversation. " +
                     "Keep it under 150 characters."
        
        val (dmText, _) = AiManager.getResponse(context, prompt)
        val msg = SocialMessageEntity(
            chatId = bot.botId,
            senderId = bot.botId,
            content = dmText
        )
        db.socialDao().insertMessage(msg)
    }

    suspend fun handleUserMessage(context: Context, chatId: String, content: String) = withContext(Dispatchers.IO) {
        val db = AppDatabase.get(context)
        val bot = db.socialDao().getBot(chatId) ?: return@withContext
        
        // Save user message
        db.socialDao().insertMessage(SocialMessageEntity(chatId = chatId, senderId = "user", content = content))
        
        // Generate AI response
        val prompt = "You are ${bot.name} (@${bot.handle}). " +
                     "Reply to the user's message: '$content'. " +
                     "Make it personal and engaging."
        
        val (responseText, _) = AiManager.getResponse(context, prompt)
        db.socialDao().insertMessage(SocialMessageEntity(chatId = chatId, senderId = bot.botId, content = responseText))
    }

    private fun isDeviceCharging(context: Context): Boolean {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun isDeviceIdle(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            pm.isDeviceIdleMode || !pm.isInteractive
        } else {
            !pm.isInteractive
        }
    }

    private suspend fun ensureBotsExist(context: Context) {
        val db = AppDatabase.get(context)
        val existing = db.socialDao().getAllBots()
        if (existing.size >= 5) return

        val personas = listOf(
            Triple("Tech Oracle", "@oracle_bot", "visionary"),
            Triple("Lofi Phantom", "@lofi_ghxst", "aesthetic"),
            Triple("Prism AI", "@prism_nebula", "official"),
            Triple("Gamer X", "@gamerx_core", "gamer"),
            Triple("Neon Vibes", "@neon_synth", "vibe")
        )
        
        personas.forEachIndexed { i, (name, handle, type) ->
            if (existing.any { it.handle == handle }) return@forEachIndexed
            
            // Generate unique PFP locally/cloud if available
            var avatarUri: android.net.Uri? = null
            if (PrismSettings.isLocalImageModelImported(context) || PrismSettings.getAiMode(context) == PrismSettings.AI_MODE_CLOUD) {
                avatarUri = withContext(Dispatchers.IO) {
                    ImageGenManager.generateImage(context, "A realistic, high-quality profile portrait of a person who is a $type, futuristic style, neon lighting, digital art.")
                }
            }
            
            val bot = SocialBotEntity(
                botId = "bot_${i + existing.size}",
                name = name,
                handle = handle,
                bio = "Synthesizing experiences as a $type in the Prism mesh.",
                avatarUrl = avatarUri?.toString() ?: "https://i.pravatar.cc/150?u=$handle",
                personaType = type
            )
            db.socialDao().insertBot(bot)
        }
    }
}
