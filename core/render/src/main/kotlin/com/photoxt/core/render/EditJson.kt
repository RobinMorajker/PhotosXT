package com.photoxt.core.render

import com.photoxt.core.model.EditRecipe
import kotlinx.serialization.json.Json

/**
 * Recipes as JSON (spec §4.10, §5.4 `edit_recipe.opsJson`): defaults left out, so a recipe is as
 * small as its edits, and unknown keys skipped, so a recipe from a newer build still opens.
 */
object EditJson {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    fun encode(recipe: EditRecipe): String = json.encodeToString(EditRecipe.serializer(), recipe)

    fun decode(text: String): EditRecipe = json.decodeFromString(EditRecipe.serializer(), text)
}
