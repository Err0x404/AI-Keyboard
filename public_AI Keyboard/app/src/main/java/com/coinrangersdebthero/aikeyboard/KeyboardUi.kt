package com.coinrangersdebthero.aikeyboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun KeyboardCandidateBar(
    suggestions: List<String>,
    isSuggesting: Boolean,
    statusMessage: String?,
    interfaceLanguage: KeyboardUiLanguage,
    onCandidateSelected: (Int) -> Unit
) {
    var isExpanded by remember(suggestions) { mutableStateOf(false) }
    val candidateListState = rememberLazyListState()

    LaunchedEffect(suggestions) {
        if (suggestions.isNotEmpty()) candidateListState.scrollToItem(0)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 42.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LazyRow(
                state = candidateListState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                itemsIndexed(suggestions) { index, suggestion ->
                    TextButton(
                        onClick = { onCandidateSelected(index) },
                        modifier = Modifier.padding(horizontal = 2.dp)
                    ) {
                        Text(
                            text = suggestion,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (suggestions.isEmpty() && statusMessage == null && !isSuggesting) {
                    item {
                        Text(
                            text = keyboardInterfaceText(
                                interfaceLanguage,
                                "輸入文字以取得聯想",
                                "Type to see suggestions"
                            ),
                            modifier = Modifier.padding(start = 8.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
            if (statusMessage != null) {
                Text(
                    text = statusMessage,
                    modifier = Modifier.widthIn(max = 110.dp).padding(horizontal = 4.dp),
                    color = if (isSuggesting) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 11.sp
                )
            }
            if (isSuggesting) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(horizontal = 6.dp).size(18.dp),
                    strokeWidth = 2.dp
                )
            }
            if (suggestions.isNotEmpty()) {
                IconButton(
                    onClick = { isExpanded = !isExpanded },
                    modifier = Modifier
                        .size(36.dp)
                        .semantics {
                            contentDescription = keyboardInterfaceText(
                                interfaceLanguage,
                                if (isExpanded) "收合完整候選清單" else "展開完整候選清單",
                                if (isExpanded) "Collapse full candidate list" else "Expand full candidate list"
                            )
                        }
                ) {
                    Text(
                        text = if (isExpanded) "⌃" else "⌄",
                        fontSize = 18.sp
                    )
                }
            }
        }

        if (isExpanded) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 144.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 2.dp)
                ) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        suggestions.forEachIndexed { index, suggestion ->
                            TextButton(
                                onClick = {
                                    isExpanded = false
                                    onCandidateSelected(index)
                                },
                                modifier = Modifier.widthIn(max = 160.dp),
                                contentPadding = PaddingValues(
                                    horizontal = 8.dp,
                                    vertical = 4.dp
                                )
                            ) {
                                Text(
                                    text = suggestion,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ScenarioMenu(
    entries: List<Pair<String, ScenarioOption>>,
    selectedOptionId: Int?,
    interfaceLanguage: KeyboardUiLanguage,
    onEntrySelected: (ScenarioOption) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = 340.dp)
                .verticalScroll(rememberScrollState())
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = keyboardInterfaceText(interfaceLanguage, "選擇情境", "Choose a scenario"),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleSmall
            )
            entries.forEach { (categoryTitle, option) ->
                val isSelected = option.id == selectedOptionId
                Button(
                    onClick = { onEntrySelected(option) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isSelected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        contentColor = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = scenarioOptionDisplayTitle(option, interfaceLanguage),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = scenarioCategoryDisplayTitle(categoryTitle, interfaceLanguage),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (isSelected) {
                            Text("✓", modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        }
    }
}
