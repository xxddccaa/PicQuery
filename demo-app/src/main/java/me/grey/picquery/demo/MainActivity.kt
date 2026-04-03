package me.grey.picquery.demo

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage

class MainActivity : ComponentActivity() {
    private val viewModel: DemoViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DemoApp(viewModel)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DemoApp(viewModel: DemoViewModel = viewModel()) {
    val state = viewModel.uiState
    val photoPermission = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }
    var pendingRole by remember { mutableStateOf<ModelRole?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.updatePermission(granted)
    }
    val modelPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val role = pendingRole
        pendingRole = null
        if (uri != null && role != null) {
            viewModel.importModel(role, uri)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.loadCachedIndexIfPossible()
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("PicQuery MobileCLIP2 Demo") })
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                StatusCard(state)
            }
            item {
                ActionSection(
                    state = state,
                    onImportImageModel = {
                        pendingRole = ModelRole.IMAGE
                        modelPicker.launch(arrayOf("*/*"))
                    },
                    onImportTextModel = {
                        pendingRole = ModelRole.TEXT
                        modelPicker.launch(arrayOf("*/*"))
                    },
                    onGrantPermission = { permissionLauncher.launch(photoPermission) },
                    onBuildIndex = viewModel::rebuildIndex
                )
            }
            item {
                SearchSection(
                    query = state.query,
                    enabled = state.modelsReady && state.indexedCount > 0,
                    onQueryChange = viewModel::updateQuery,
                    onSearch = viewModel::runSearch
                )
            }
            if (state.indexing) {
                item {
                    IndexingSection(state)
                }
            }
            items(state.searchResults, key = { it.id }) { result ->
                SearchResultRow(result)
            }
        }
    }
}

@Composable
private fun StatusCard(state: DemoUiState) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Workflow", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(state.status)
            Text("Image model: ${state.imageModelPath ?: "not imported"}")
            Text("Text model: ${state.textModelPath ?: "not imported"}")
            Text("Photo permission: ${if (state.permissionGranted) "granted" else "missing"}")
            Text("Indexed images: ${state.indexedCount}")
            state.runtimeInfo?.let {
                Text("Runtime: ${it.imageBackend} (GPU active: ${it.gpuActive})")
            }
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun ActionSection(
    state: DemoUiState,
    onImportImageModel: () -> Unit,
    onImportTextModel: () -> Unit,
    onGrantPermission: () -> Unit,
    onBuildIndex: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onImportImageModel, modifier = Modifier.weight(1f)) {
                Text("Import image model")
            }
            Button(onClick = onImportTextModel, modifier = Modifier.weight(1f)) {
                Text("Import text model")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onGrantPermission, modifier = Modifier.weight(1f)) {
                Text(if (state.permissionGranted) "Photos ready" else "Grant photos")
            }
            Button(
                onClick = onBuildIndex,
                enabled = state.modelsReady && state.permissionGranted && !state.indexing,
                modifier = Modifier.weight(1f)
            ) {
                Text(if (state.indexedCount == 0) "Build index" else "Rebuild index")
            }
        }
    }
}

@Composable
private fun SearchSection(
    query: String,
    enabled: Boolean,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Describe the image you want") },
            placeholder = { Text("e.g. sunset beach, cat on sofa, laptop on desk") },
            enabled = enabled
        )
        Button(onClick = onSearch, enabled = enabled && query.isNotBlank()) {
            Text("Search gallery")
        }
    }
}

@Composable
private fun IndexingSection(state: DemoUiState) {
    Card {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CircularProgressIndicator()
            Column {
                Text("Indexing in progress", fontWeight = FontWeight.Bold)
                Text("${state.indexedCount} / ${state.totalToIndex}")
            }
        }
    }
}

@Composable
private fun SearchResultRow(result: DemoSearchResult) {
    Card {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = result.uri,
                contentDescription = result.displayName,
                modifier = Modifier
                    .size(92.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentScale = ContentScale.Crop
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(result.displayName, fontWeight = FontWeight.Bold)
                Text("score %.4f".format(result.score))
                Text(result.uri.toString(), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
