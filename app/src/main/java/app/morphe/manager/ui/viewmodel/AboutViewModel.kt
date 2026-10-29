/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.viewmodel

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Public
import androidx.lifecycle.ViewModel
import app.morphe.manager.util.MANAGER_REPO_URL
import compose.icons.FontAwesomeIcons
import compose.icons.fontawesomeicons.Brands
import compose.icons.fontawesomeicons.brands.Github

data class SocialLink(
    val name: String,
    val url: String,
    val preferred: Boolean = false,
)

class AboutViewModel : ViewModel() {
    companion object {
        val socials: List<SocialLink> = listOf(
            SocialLink(name = "GitHub", url = MANAGER_REPO_URL, preferred = true),
            SocialLink(name = "Releases", url = "$MANAGER_REPO_URL/releases"),
            SocialLink(name = "Report a bug", url = "$MANAGER_REPO_URL/issues"),
            SocialLink(name = "Contribute", url = "$MANAGER_REPO_URL/blob/main/CONTRIBUTING.md"),
            // Keep the inherited project's attribution accessible via PV7's own credits.
            SocialLink(name = "Credits", url = "$MANAGER_REPO_URL#credits"),
        )

        private val socialIcons = mapOf(
            "GitHub" to FontAwesomeIcons.Brands.Github,
            "Releases" to Icons.AutoMirrored.Outlined.Article,
            "Report a bug" to Icons.AutoMirrored.Outlined.Article,
            "Contribute" to Icons.Outlined.Public,
            "Credits" to Icons.Outlined.Language,
        )

        fun getSocialIcon(name: String) = socialIcons[name] ?: Icons.Outlined.Language
    }
}
