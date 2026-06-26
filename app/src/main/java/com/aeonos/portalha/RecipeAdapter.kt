package com.aeonos.portalha

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import org.json.JSONObject

class RecipeAdapter(
    private val mealieUrl: String,
    private val mealieToken: String,
    private val onRecipeClick: (JSONObject) -> Unit
) : RecyclerView.Adapter<RecipeAdapter.RecipeViewHolder>() {

    private var allItems = listOf<JSONObject>()
    private var items = listOf<JSONObject>()

    fun setRecipes(newItems: List<JSONObject>) {
        allItems = newItems
        items = newItems
        notifyDataSetChanged()
    }

    fun filter(query: String) {
        items = if (query.isEmpty()) {
            allItems
        } else {
            val lowerQuery = query.lowercase()
            allItems.filter { recipe ->
                val name = recipe.optString("name", "").lowercase()
                val desc = recipe.optString("description", "").lowercase()
                name.contains(lowerQuery) || desc.contains(lowerQuery)
            }
        }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecipeViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_recipe, parent, false)
        return RecipeViewHolder(view)
    }

    override fun onBindViewHolder(holder: RecipeViewHolder, position: Int) {
        val recipe = items[position]
        holder.bind(recipe, mealieUrl, mealieToken, onRecipeClick)
    }

    override fun getItemCount(): Int = items.size

    class RecipeViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivThumbnail: ImageView = itemView.findViewById(R.id.iv_recipe_thumbnail)
        private val tvTitle: TextView = itemView.findViewById(R.id.tv_recipe_title)
        private val tvTime: TextView = itemView.findViewById(R.id.tv_recipe_time)

        fun bind(recipe: JSONObject, mealieUrl: String, mealieToken: String, onRecipeClick: (JSONObject) -> Unit) {
            val title = recipe.optString("name", "Unnamed Recipe")
            tvTitle.text = title

            val prep = recipe.optString("prepTime", "").trim()
            val total = recipe.optString("totalTime", "").trim()
            val timeText = when {
                total.isNotEmpty() -> "Total: $total"
                prep.isNotEmpty() -> "Prep: $prep"
                else -> "Quick & Easy"
            }
            tvTime.text = timeText

            val id = recipe.optString("id", "")
            if (id.isNotEmpty() && mealieUrl.isNotEmpty()) {
                val imageUrl = "${mealieUrl.trimEnd('/')}/api/media/recipes/$id/images/min-original.webp"
                
                val glideUrl = if (mealieToken.isNotEmpty()) {
                    GlideUrl(
                        imageUrl,
                        LazyHeaders.Builder()
                            .addHeader("Authorization", "Bearer $mealieToken")
                            .build()
                    )
                } else {
                    imageUrl
                }

                Glide.with(itemView.context)
                    .load(glideUrl)
                    .placeholder(android.R.drawable.ic_menu_gallery)
                    .error(android.R.drawable.ic_menu_gallery)
                    .into(ivThumbnail)
            } else {
                ivThumbnail.setImageResource(android.R.drawable.ic_menu_gallery)
            }

            itemView.setOnClickListener { onRecipeClick(recipe) }
        }
    }
}
