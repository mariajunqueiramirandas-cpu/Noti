package app.noti.ui.assaad

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.noti.assaad.ASSAAD_CREDIT
import app.noti.assaad.AssaadBatch
import app.noti.assaad.AssaadImage
import app.noti.assaad.AssaadLocalBrowser
import app.noti.assaad.AssaadQuestion
import app.noti.assaad.AssaadSession
import app.noti.assaad.assaadOperationProblem
import app.noti.ui.common.NotiButton
import app.noti.ui.common.NotiDialogFrame
import app.noti.ui.common.NotiFilterChip
import app.noti.ui.common.NotiTextButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

private enum class QuestionFilter(val title: String, val parameter: String, val optionList: String = "") {
    Subject("Subject", "category_id", "categories"),
    Topic("Topic", "subcategory_id"),
    Tag("Tag", "tag_ids", "tags"),
    Exam("Exam", "institution_id", "institutions"),
    Year("Year", "years", "years"),
    Difficulty("Difficulty", "difficulties"),
}

private data class FilterOption(val id: String, val label: String, val topics: List<FilterOption> = emptyList())

private val difficultyOptions = listOf("Very easy", "Easy", "Medium", "Hard", "Very hard")
    .mapIndexed { index, label -> FilterOption((index + 1).toString(), label) }

private fun JSONObject.options(name: String): List<FilterOption> {
    val entries = optJSONArray(name) ?: return emptyList()
    return (0 until entries.length()).map { index ->
        val value = entries.getJSONObject(index)
        FilterOption(value.getString("id"), value.getString("label"), value.options("topics"))
    }
}

private data class SearchPage(
    val number: Int,
    val last: Int?,
    val total: Int?,
    val batch: AssaadBatch?,
    val query: Map<String, String>,
)

@Composable
internal fun AssaadQuestionFinder(session: AssaadSession, browser: AssaadLocalBrowser, onCanvas: () -> Unit) {
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val list = rememberLazyListState()
    val figures = remember(session, scope) { AssaadFigures(session, scope) }
    var options by remember { mutableStateOf<JSONObject?>(null) }
    var optionsError by remember { mutableStateOf<String?>(null) }
    var optionsAttempt by remember { mutableIntStateOf(0) }
    var search by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(emptyMap<QuestionFilter, FilterOption>()) }
    var picker by remember { mutableStateOf<QuestionFilter?>(null) }
    var preview by remember { mutableStateOf<AssaadQuestion?>(null) }
    var page by remember { mutableStateOf<SearchPage?>(null) }
    var searching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val query = selected.mapKeys { it.key.parameter }.mapValues { it.value.id } + ("search" to search.trim())
    val result = page?.takeIf { it.query == query }
    val filtersReady = options != null || optionsError != null
    val canSearch = !searching && filtersReady && !session.hasPendingAnswer
    val filterOptions = remember(options, selected[QuestionFilter.Subject]) {
        QuestionFilter.entries.associateWith { filter ->
            when (filter) {
                QuestionFilter.Topic -> selected[QuestionFilter.Subject]?.topics.orEmpty()
                QuestionFilter.Difficulty -> difficultyOptions
                QuestionFilter.Year -> options?.options(filter.optionList).orEmpty().sortedByDescending { it.id }
                else -> options?.options(filter.optionList).orEmpty().sortedBy { it.label.lowercase() }
            }
        }
    }

    LaunchedEffect(browser, optionsAttempt) {
        optionsError = null
        try {
            val response = browser.operation(JSONObject().put("operation", "metadata"))
            if (response.optBoolean("ok")) options = response.getJSONObject("options")
            else optionsError = assaadOperationProblem(response)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            optionsError = "Couldn't load the filters. Try again."
        }
    }

    fun fetch(number: Int) {
        if (!canSearch || searching) return
        focus.clearFocus()
        searching = true
        error = null
        scope.launch {
            try {
                val input = JSONObject().put("operation", "search").put("page", number).put("filters", JSONObject(query))
                val response = browser.operation(input)
                if (response.optBoolean("ok")) {
                    val value = response.getJSONObject("batch")
                    val batch = value.takeIf { it.getJSONArray("questions").length() > 0 }?.let(AssaadBatch::parse)
                    val total = response.optInt("total", -1).takeIf { it >= 0 }
                    val last = response.optInt("lastPage", -1).takeIf { it >= 0 }
                    page = SearchPage(number, last, total, batch, query)
                } else {
                    error = assaadOperationProblem(response)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                error = "Couldn't load questions. Try again."
            } finally {
                searching = false
            }
            list.scrollToItem(1)
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            state = list,
            modifier = Modifier.widthIn(max = 780.dp).fillMaxSize().imePadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item("filters") {
                ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Find questions", Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
                        OutlinedTextField(
                            value = search,
                            onValueChange = { search = it.take(160); error = null },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !searching,
                            label = { Text("Search question text") },
                            leadingIcon = { Icon(Icons.Outlined.Search, null) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { fetch(1) }),
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for ((field, values) in filterOptions) {
                                val value = selected[field]
                                NotiFilterChip(
                                    selected = value != null,
                                    onClick = { picker = field },
                                    enabled = !searching && values.isNotEmpty(),
                                    label = { Text(value?.let { "${field.title} · ${it.label}" } ?: field.title,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                )
                            }
                        }
                        optionsError?.let {
                            Text(it, color = MaterialTheme.colorScheme.error)
                            NotiTextButton(onClick = { optionsAttempt++ }, enabled = !searching) { Text("Reload filters") }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NotiButton(onClick = { fetch(1) }, enabled = canSearch) { Text(if (searching) "Searching…" else "Search") }
                            NotiTextButton(
                                enabled = !searching && (search.isNotEmpty() || selected.isNotEmpty()),
                                onClick = { search = ""; selected = emptyMap(); error = null },
                            ) { Text("Clear filters") }
                        }
                        if (!filtersReady) Text("Loading Assaad filters…")
                    }
                }
            }
            item("results") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Questions", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                    val count = result?.total ?: result?.batch?.questions?.size
                    val message = when {
                        searching -> "Loading questions…"
                        error != null -> error
                        result == null -> "Run a search to see matching questions."
                        result.batch == null -> if (result.number == 1) "No questions match these filters." else "No questions on this page."
                        count == 1 -> "1 question"
                        count != null -> "$count questions"
                        else -> null
                    }
                    message?.let {
                        Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    result?.batch?.let { batch ->
                        NotiButton(
                            onClick = {
                                if (session.saveQuestionPage(batch)) onCanvas()
                                else error = session.problem ?: "This page couldn't be saved."
                            },
                            enabled = !searching && session.libraryLoaded,
                        ) { Text("Study this page on canvas") }
                    }
                    if (result != null && (result.number > 1 || (result.last ?: 0) > 1)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            NotiTextButton(onClick = { fetch(result.number - 1) }, enabled = canSearch && result.number > 1) { Text("Previous") }
                            Text("Page ${result.number}" + (result.last?.let { " of $it" } ?: ""))
                            NotiTextButton(onClick = { fetch(result.number + 1) }, enabled = canSearch && result.number < (result.last ?: 0)) { Text("Next") }
                        }
                    }
                }
            }
            items(result?.batch?.questions.orEmpty(), key = AssaadQuestion::id) { question ->
                OutlinedCard(onClick = { preview = question }, enabled = !searching) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(question.source.ifBlank { "Plataforma Assaad" }, style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary)
                        Text(question.prompt.ifBlank { "Image question" }, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        if (question.tags.isNotEmpty()) {
                            Text(question.tags.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }

    picker?.let { field ->
        FilterPicker(field.title, filterOptions.getValue(field), onDismiss = { picker = null }) { value ->
            val other = if (field == QuestionFilter.Subject && value?.id != selected[field]?.id) {
                selected - field - QuestionFilter.Topic
            } else selected - field
            selected = if (value == null) other else other + (field to value)
            error = null
            picker = null
        }
    }
    preview?.let { question ->
        NotiDialogFrame(
            onDismissRequest = { preview = null },
            title = { Text(question.source.ifBlank { "Question" }) },
            maxWidth = 640.dp,
            actions = { NotiTextButton(onClick = { preview = null }) { Text("Close") } },
        ) {
            Text(question.prompt)
            QuestionImages(question.images, figures)
            for (alternative in question.alternatives) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${alternative.label}) ${alternative.text}")
                    QuestionImages(alternative.images, figures)
                }
            }
            Text(ASSAAD_CREDIT, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun FilterPicker(title: String, options: List<FilterOption>, onDismiss: () -> Unit, select: (FilterOption?) -> Unit) {
    var search by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (options.size > 10) {
                    OutlinedTextField(search, { search = it }, label = { Text("Find an option") }, singleLine = true)
                }
                LazyColumn(Modifier.heightIn(max = 280.dp)) {
                    item { NotiTextButton(onClick = { select(null) }, modifier = Modifier.fillMaxWidth()) { Text("Any") } }
                    items(options.filter { it.label.contains(search.trim(), ignoreCase = true) }, key = FilterOption::id) { option ->
                        NotiTextButton(onClick = { select(option) }, modifier = Modifier.fillMaxWidth()) { Text(option.label) }
                    }
                }
            }
        },
        confirmButton = { NotiTextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun QuestionImages(images: List<AssaadImage>, figures: AssaadFigures) {
    for (image in images) {
        val bitmap = image.url?.let(figures::get)
        if (bitmap != null) {
            Image(bitmap.asImageBitmap(), image.alt.ifBlank { "Question image" }, Modifier.fillMaxWidth().heightIn(max = 300.dp))
        } else {
            Text(image.alt.ifBlank { "Image unavailable" }, style = MaterialTheme.typography.bodySmall)
        }
    }
}
