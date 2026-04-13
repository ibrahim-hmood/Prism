package com.prism.launcher.social

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.prism.launcher.AppDatabase
import com.prism.launcher.databinding.ActivitySocialComposeBinding
import com.prism.launcher.messaging.VisionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class NebulaComposeActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySocialComposeBinding
    private var selectedImageUri: Uri? = null

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            selectedImageUri = uri
            binding.imagePreviewContainer.visibility = View.VISIBLE
            binding.imgPreview.setImageURI(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySocialComposeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnClose.setOnClickListener { finish() }
        
        binding.btnAddImage.setOnClickListener {
            pickImage.launch("image/*")
        }

        binding.btnRemoveImage.setOnClickListener {
            selectedImageUri = null
            binding.imagePreviewContainer.visibility = View.GONE
        }

        binding.btnPost.setOnClickListener {
            performPost()
        }
        
        // Load user avatar (mock for now, or from prefs)
        binding.userAvatar.setImageResource(android.R.drawable.ic_menu_gallery)
    }

    private fun performPost() {
        val content = binding.composeInput.text.toString().trim()
        if (content.isEmpty() && selectedImageUri == null) {
            Toast.makeText(this, "Can't post an empty thought.", Toast.LENGTH_SHORT).show()
            return
        }

        binding.btnPost.isEnabled = false
        
        lifecycleScope.launch {
            val db = AppDatabase.get(this@NebulaComposeActivity)
            
            // Analyze image for AI bots to "see" it
            var visionTags = ""
            selectedImageUri?.let { uri ->
                visionTags = withContext(Dispatchers.IO) {
                    VisionService.analyzeImage(this@NebulaComposeActivity, uri)
                }
            }

            val post = SocialPostEntity(
                postId = UUID.randomUUID().toString(),
                authorId = "user",
                authorName = "You",
                authorHandle = "@user",
                authorAvatarUrl = null,
                content = content,
                imageUrl = selectedImageUri?.toString(),
                isUserPost = true
            )

            withContext(Dispatchers.IO) {
                db.socialDao().insertPost(post)
                
                // Trigger AI interaction if visionTags are present
                if (visionTags.isNotEmpty() || content.isNotEmpty()) {
                    triggerBotReactions(post, visionTags)
                }
            }

            Toast.makeText(this@NebulaComposeActivity, "Posted to Mesh!", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private suspend fun triggerBotReactions(post: SocialPostEntity, visionTags: String) {
        // Find 1-2 random bots to comment
        val db = AppDatabase.get(this)
        val bots = db.socialDao().getAllBots()
        if (bots.isEmpty()) return
        
        val count = (1..2).random()
        repeat(count) {
            val bot = bots.random()
            val contextText = if (visionTags.isNotEmpty()) "The user posted an image. $visionTags" else ""
            val prompt = "You are ${bot.name} (@${bot.handle}). " +
                         "Comment on the user's post: '${post.content}'. $contextText " +
                         "Be conversational."
            
            // Trigger immediately for responsiveness
            com.prism.launcher.social.NebulaSocialManager.generateBotComment(this, bot, post, visionTags)
        }
    }
}
