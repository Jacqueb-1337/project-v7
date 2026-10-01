package app.morphe.manager.ui.screen.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.morphe.manager.domain.pv7.Pv7ModuleBadge
import app.morphe.manager.domain.pv7.Pv7ResolutionPlan
import app.morphe.manager.ui.screen.shared.AppDialog
import app.morphe.manager.ui.screen.shared.AppDialogButtonRow
import app.morphe.manager.ui.screen.shared.DialogPadding
import app.morphe.manager.ui.screen.shared.SemanticTone
import app.morphe.manager.ui.screen.shared.StatusBadge

@Composable
internal fun Pv7CompatibilityDialog(
    packageName: String,
    version: String?,
    resolution: Pv7ResolutionPlan,
    explicitModuleIds: Set<String>,
    catalogIssues: List<String>,
    onToggleModule: (String) -> Unit,
    onProceed: () -> Unit,
    onDismiss: () -> Unit
) {
    val shownModules = resolution.visibleModules.filter {
        it.selected || it.badge != Pv7ModuleBadge.UNTESTED
    }
    val testedRecommendations = resolution.profile?.recommendedModules.orEmpty().toSet()

    AppDialog(
        onDismissRequest = onDismiss,
        title = "Project V7 compatibility",
        description = buildString {
            append(packageName)
            if (!version.isNullOrBlank()) append(" • ").append(version)
        },
        padding = DialogPadding.Compact,
        scrollable = false,
        footer = {
            AppDialogButtonRow(
                primaryText = "Continue",
                onPrimaryClick = onProceed,
                secondaryText = "Cancel",
                onSecondaryClick = onDismiss
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val profile = resolution.profile
            if (profile != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            text = "Maintainer-tested profile",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = profile.notes?.takeIf { it.isNotBlank() }
                                ?: "The selected defaults have been tested for this app/version.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                Text(
                    text = "No tested game profile matched. Compatible general and engine-specific modules are shown below.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            val allIssues = (catalogIssues + resolution.issues).distinct()
            if (allIssues.isNotEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Catalog warnings",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        allIssues.forEach { issue ->
                            Text(
                                text = "• " + issue,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }

            shownModules.forEachIndexed { index, row ->
                val module = row.manifest
                val isExplicit = module.id in explicitModuleIds
                val isRequired = row.selected && !isExplicit
                val isRecommended = module.id in testedRecommendations
                val isSuggested = !isRecommended && row.badge == Pv7ModuleBadge.ENGINE_SUGGESTED
                val canToggle = !isRequired

                Surface(
                    onClick = { if (canToggle) onToggleModule(module.id) },
                    enabled = canToggle,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = if (row.selected) {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = row.selected,
                            onCheckedChange = if (canToggle) {
                                { onToggleModule(module.id) }
                            } else null,
                            enabled = canToggle
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = module.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            if (module.description.isNotBlank()) {
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    text = module.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                when {
                                    isRequired -> StatusBadge(
                                        text = "Required",
                                        tone = SemanticTone.Warning
                                    )
                                    isRecommended -> StatusBadge(
                                        text = "Recommended",
                                        tone = SemanticTone.Primary
                                    )
                                    isSuggested -> StatusBadge(
                                        text = "Suggested",
                                        tone = SemanticTone.Success
                                    )
                                    else -> StatusBadge(
                                        text = "Optional",
                                        tone = SemanticTone.Neutral
                                    )
                                }
                                StatusBadge(
                                    text = module.version,
                                    tone = SemanticTone.Neutral
                                )
                            }
                        }
                    }
                }

                if (index != shownModules.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 8.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                    )
                }
            }

            if (shownModules.isEmpty()) {
                Text(
                    text = "No Project V7 compatibility modules matched this APK.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}