package com.prism.launcher.social

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.EditText
import android.widget.Button
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.prism.launcher.AppDatabase
import com.prism.launcher.databinding.PageSocialNebulaBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The 'Nebula' AI Social Media page.
 * Implements Twitter-like UI (Green theme) and strictly follows AI generation rules:
 * - Local Models: Generate only on SwipeRefresh (Foreground) or Idle/Charging (Background).
 * - Cloud Models: Generate on SwipeRefresh, Background, or Runtime.
 */
class NebulaSocialPageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val binding = PageSocialNebulaBinding.inflate(LayoutInflater.from(context), this, true)
    private val db = AppDatabase.get(context)
    
    private lateinit var feedAdapter: NebulaPostAdapter
    private lateinit var suggestedAdapter: NebulaSuggestedUserAdapter
    private lateinit var recentChatsAdapter: NebulaRecentChatsAdapter

    private val viewStack = java.util.Stack<View>()

    private fun resolveAttr(attr: Int): Int {
        val typedValue = android.util.TypedValue()
        context.theme.resolveAttribute(attr, typedValue, true)
        return typedValue.data
    }

    init {
        setupTopBar()
        setupBottomNav()
        setupHomeView()
        
        // Initial state
        showHome()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible && currentViewMode == "home") {
            loadHomeData()
        }
    }

    private fun setupTopBar() {
        binding.socialSearch.setOnClickListener {
            com.prism.launcher.PrismDialogFactory.show(context, "Search Nebula", "Find AI personas or posts...")
        }
    }

    private var currentViewMode = "home"

    private fun setupBottomNav() {
        binding.navHome.setOnClickListener { showHome() }
        binding.navDms.setOnClickListener { showDmList() }
        
        binding.socialFab.setOnClickListener {
            if (currentViewMode == "dms") {
                showSuggestedAccountsPicker()
            } else {
                val intent = android.content.Intent(context, NebulaComposeActivity::class.java)
                context.startActivity(intent)
            }
        }
    }

    private fun showSuggestedAccountsPicker() {
        findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
            val bots = withContext(Dispatchers.IO) { db.socialDao().getAllBots() }
            val botNames = bots.map { "${it.name} (${it.handle})" }
            
            com.prism.launcher.PrismDialogFactory.show(
                context, 
                "New Message", 
                "Choose an AI to message:",
                customView = android.widget.ListView(context).apply {
                    adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_list_item_1, botNames)
                    setOnItemClickListener { _, _, which, _ ->
                        showChatRoom(bots[which].botId)
                    }
                }
            )
        }
    }

    private fun setupHomeView() {
        // Suggested Users
        suggestedAdapter = NebulaSuggestedUserAdapter(
            onProfileClick = { bot -> showProfile(bot.botId) },
            onFollowClick = { bot -> 
                findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
                    withContext(Dispatchers.IO) {
                        db.socialDao().follow(SocialFollowEntity(botId = bot.botId))
                    }
                }
            }
        )
        binding.suggestedUsersList.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        binding.suggestedUsersList.adapter = suggestedAdapter

        // Feed
        feedAdapter = NebulaPostAdapter(
            onProfileClick = { id -> showProfile(id) },
            onPostClick = { post -> showPostDetail(post) }
        ).apply {
            setOnInteractionLongPressListener(object : NebulaPostAdapter.OnInteractionLongPressListener {
                override fun onLongPress(anchor: View, postId: String, type: String) {
                    showInteractionBubble(anchor, postId, type)
                }

                override fun onInteraction(postId: String, type: String, actorId: String) {
                    findViewTreeLifecycleOwner()?.lifecycleScope?.launch(Dispatchers.IO) {
                        db.socialDao().insertInteraction(SocialInteractionEntity(
                            postId = postId,
                            actorId = actorId,
                            actorName = "You",
                            type = type
                        ))
                    }
                }
            })
        }
        binding.socialFeed.layoutManager = LinearLayoutManager(context)
        binding.socialFeed.adapter = feedAdapter

        binding.socialRefresh.setOnRefreshListener {
            triggerManualRefresh()
        }
    }

    private fun showInteractionBubble(anchor: View, postId: String, type: String) {
        findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
            val interactions = withContext(Dispatchers.IO) { db.socialDao().getInteractions(postId, type) }
            val actorNames = interactions.map { it.actorName }
            
            NebulaInteractionBubble.show(
                anchor,
                if (type == "like") "Starred By" else "Shared By",
                actorNames
            )
        }
    }

    private fun showHome() {
        currentViewMode = "home"
        binding.homeView.visibility = View.VISIBLE
        binding.socialFab.visibility = View.VISIBLE
        // Hide other dynamic views if any
        clearDynamicViews()
        
        // Update Nav UI
        binding.iconHome.imageTintList = android.content.res.ColorStateList.valueOf(resolveAttr(com.prism.launcher.R.attr.prismAccent))
        binding.iconDms.imageTintList = android.content.res.ColorStateList.valueOf(resolveAttr(com.prism.launcher.R.attr.prismTextPrimary))

        loadHomeData()
    }

    private fun loadHomeData() {
        findViewTreeLifecycleOwner()?.lifecycleScope?.launch(Dispatchers.Main) {
            val bots = withContext(Dispatchers.IO) { db.socialDao().getAllBots() }
            val posts = withContext(Dispatchers.IO) { db.socialDao().getAllPosts() }
            suggestedAdapter.submitList(bots)
            feedAdapter.submitList(posts)
        }
    }

    private fun showDmList() {
        currentViewMode = "dms"
        binding.homeView.visibility = View.GONE
        binding.socialFab.visibility = View.VISIBLE
        clearDynamicViews()

        // Inflate/Add DM List if needed
        val rv = androidx.recyclerview.widget.RecyclerView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            layoutManager = LinearLayoutManager(context)
        }
        recentChatsAdapter = NebulaRecentChatsAdapter { chatId -> showChatRoom(chatId) }
        rv.adapter = recentChatsAdapter
        
        binding.socialContentContainer.addView(rv)
        
        // Update Nav UI
        binding.iconHome.imageTintList = android.content.res.ColorStateList.valueOf(resolveAttr(com.prism.launcher.R.attr.prismTextPrimary))
        binding.iconDms.imageTintList = android.content.res.ColorStateList.valueOf(resolveAttr(com.prism.launcher.R.attr.prismAccent))

        findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
            val chats = withContext(Dispatchers.IO) { db.socialDao().getRecentChats() }
            recentChatsAdapter.submitList(chats)
        }
    }

    private fun showProfile(botId: String) {
        currentViewMode = "profile"
        binding.homeView.visibility = View.GONE
        binding.socialFab.visibility = View.GONE
        clearDynamicViews()

        val profileBinding = com.prism.launcher.databinding.PageSocialProfileBinding.inflate(LayoutInflater.from(context), binding.socialContentContainer, true)
        
        findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
            val bot = withContext(Dispatchers.IO) { db.socialDao().getBot(botId) }
            val posts = withContext(Dispatchers.IO) { db.socialDao().getPostsByAuthor(botId) }
            
            if (bot != null) {
                profileBinding.profileName.text = bot.name
                profileBinding.profileHandle.text = bot.handle
                profileBinding.profileBio.text = bot.bio
                
                val pAdapter = NebulaPostAdapter(onProfileClick = {}, onPostClick = { showPostDetail(it) })
                profileBinding.profilePostsList.layoutManager = LinearLayoutManager(context)
                profileBinding.profilePostsList.adapter = pAdapter
                pAdapter.submitList(posts)

                profileBinding.profileDmBtn.setOnClickListener { showChatRoom(botId) }
            }
        }
    }

    private fun showPostDetail(post: SocialPostEntity) {
        currentViewMode = "post_detail"
        binding.homeView.visibility = View.GONE
        binding.socialFab.visibility = View.GONE
        clearDynamicViews()

        val detailBinding = com.prism.launcher.databinding.PageSocialPostDetailBinding.inflate(LayoutInflater.from(context), binding.socialContentContainer, true)
        
        // Bind Main Post
        detailBinding.mainPostView.postAuthorName.text = post.authorName
        detailBinding.mainPostView.postContent.text = post.content
        detailBinding.mainPostView.postTime.text = "Just now"

        // Setup Comments
        val cAdapter = NebulaCommentAdapter { showProfile(it) }
        detailBinding.commentsList.layoutManager = LinearLayoutManager(context)
        detailBinding.commentsList.adapter = cAdapter

        detailBinding.btnPostComment.setOnClickListener {
            val text = detailBinding.commentInput.text.toString()
            if (text.isNotEmpty()) {
                findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
                    withContext(Dispatchers.IO) {
                        db.socialDao().insertComment(SocialCommentEntity(
                            postId = post.postId,
                            authorId = "user",
                            authorName = "You",
                            authorHandle = "@user",
                            authorAvatarUrl = null,
                            content = text
                        ))
                    }
                    detailBinding.commentInput.text.clear()
                    loadComments(post.postId, cAdapter)
                }
            }
        }

        loadComments(post.postId, cAdapter)
    }

    private fun loadComments(postId: String, adapter: NebulaCommentAdapter) {
        findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
            val comments = withContext(Dispatchers.IO) { db.socialDao().getCommentsForPost(postId) }
            adapter.submitList(comments)
        }
    }

    private fun showChatRoom(chatId: String) {
        currentViewMode = "chat"
        binding.homeView.visibility = View.GONE
        binding.socialFab.visibility = View.GONE
        clearDynamicViews()

        // For simplicity, using a basic RecyclerView + Input.
        // Usually we'd use a specific layout.
        // DM Chat Room View
        val chatBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            setBackgroundColor(resolveAttr(com.prism.launcher.R.attr.prismBackground))
        }

        val chatRv = androidx.recyclerview.widget.RecyclerView(context).apply {
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
            layoutManager = LinearLayoutManager(context)
        }
        val chatAdapter = NebulaChatAdapter()
        chatRv.adapter = chatAdapter
        chatBox.addView(chatRv)

        val inputArea = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 16, 16, 16)
        }
        val et = android.widget.EditText(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            hint = "Send a message..."
            setTextColor(resolveAttr(com.prism.launcher.R.attr.prismTextPrimary))
            setHintTextColor(resolveAttr(com.prism.launcher.R.attr.prismTextSecondary))
        }
        val btn = android.widget.ImageButton(context).apply {
            setImageResource(android.R.drawable.ic_menu_send)
            imageTintList = android.content.res.ColorStateList.valueOf(resolveAttr(com.prism.launcher.R.attr.prismBackground))
            background = com.prism.launcher.NeonGlowDrawable(
                color = resolveAttr(com.prism.launcher.R.attr.prismAccent), 
                cornerRadius = 24f, 
                strokeWidth = 0f
            )
            val pad = (12 * context.resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            
            setOnClickListener {
                val txt = et.text.toString()
                if (txt.isNotEmpty()) {
                    findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
                        NebulaSocialManager.handleUserMessage(context, chatId, txt)
                        et.text.clear()
                        loadMessages(chatId, chatAdapter, chatRv)
                    }
                }
            }
        }
        inputArea.addView(et)
        inputArea.addView(btn)
        chatBox.addView(inputArea)

        binding.socialContentContainer.addView(chatBox)
        loadMessages(chatId, chatAdapter, chatRv)
    }

    private fun loadMessages(chatId: String, adapter: NebulaChatAdapter, rv: androidx.recyclerview.widget.RecyclerView) {
        findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
            val msgs = withContext(Dispatchers.IO) { db.socialDao().getMessagesForChat(chatId) }
            adapter.submitList(msgs) {
                rv.scrollToPosition(msgs.size - 1)
            }
        }
    }

    private fun clearDynamicViews() {
        // Remove everything except homeView
        for (i in binding.socialContentContainer.childCount - 1 downTo 0) {
            val child = binding.socialContentContainer.getChildAt(i)
            if (child.id != binding.homeView.id) {
                binding.socialContentContainer.removeViewAt(i)
            }
        }
    }

    private fun triggerManualRefresh() {
        findViewTreeLifecycleOwner()?.lifecycleScope?.launch(Dispatchers.Main) {
            binding.socialRefresh.isRefreshing = true
            withContext(Dispatchers.IO) {
                NebulaSocialManager.generateNewContent(context, manual = true)
            }
            loadHomeData()
            binding.socialRefresh.isRefreshing = false
        }
    }
}
