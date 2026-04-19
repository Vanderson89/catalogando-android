package com.example.catalogando

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import java.io.File

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

// --- FUNÇÃO NINJA: Salva a foto da câmera no celular ---
fun salvarBitmapNoCache(context: Context, bitmap: Bitmap): Uri {
    val file = File(context.cacheDir, "foto_camera_${System.currentTimeMillis()}.jpg")
    file.outputStream().use { out ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
    }
    return Uri.fromFile(file) // Devolve o endereço da foto para salvarmos no Banco!
}

// --- IDENTIDADE VISUAL ---
val LightPrimary = Color(0xFF6D4C41)
val LightSecondary = Color(0xFFD7CCC8)
val LightBackground = Color(0xFFEFEBE9)
val LightSurface = Color(0xFFFFFFFF)
val LightOnSurface = Color(0xFF3E2723)

val DarkPrimary = Color(0xFFD7CCC8)
val DarkSecondary = Color(0xFF5D4037)
val DarkBackground = Color(0xFF201A18)
val DarkSurface = Color(0xFF302522)
val DarkOnSurface = Color(0xFFEFEBE9)

private val LightColorScheme = lightColorScheme(primary = LightPrimary, secondaryContainer = LightSecondary, background = LightBackground, surface = LightSurface, onSurface = LightOnSurface, surfaceVariant = LightSurface)
private val DarkColorScheme = darkColorScheme(primary = DarkPrimary, secondaryContainer = DarkSecondary, background = DarkBackground, surface = DarkSurface, onSurface = DarkOnSurface, surfaceVariant = DarkSurface)

@Composable
fun CatalogoTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(colorScheme = colorScheme, content = content)
}

// --- BANCO DE DADOS ROOM ---
@Entity(tableName = "tabela_acervo")
data class ItemAcervo(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val titulo: String,
    val responsavel: String,
    val tipo: String,
    val imagemUri: String? = null,
    var anotacoes: String? = null
)

@Dao
interface AcervoDao {
    @Query("SELECT * FROM tabela_acervo ORDER BY id DESC")
    fun buscarTodos(): Flow<List<ItemAcervo>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun salvar(item: ItemAcervo)

    @Delete
    suspend fun deletar(item: ItemAcervo)
}

@Database(entities = [ItemAcervo::class], version = 1, exportSchema = false)
abstract class AcervoDatabase : RoomDatabase() {
    abstract fun acervoDao(): AcervoDao
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CatalogoTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    TelaPrincipal()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TelaPrincipal() {
    val context = LocalContext.current
    val db = remember { Room.databaseBuilder(context.applicationContext, AcervoDatabase::class.java, "banco_catalogo_v8.db").build() }
    val dao = db.acervoDao()
    val coroutineScope = rememberCoroutineScope()
    val listaCompleta by dao.buscarTodos().collectAsState(initial = emptyList())

    var pesquisaQuery by remember { mutableStateOf("") }
    var filtroAtual by remember { mutableStateOf("Tudo") }
    var itemParaDetalhes by remember { mutableStateOf<ItemAcervo?>(null) }

    var tituloInput by remember { mutableStateOf("") }
    var responsavelInput by remember { mutableStateOf("") }
    var tipoSelecionado by remember { mutableStateOf("Livro") }
    var imagemSelecionadaUri by remember { mutableStateOf<Uri?>(null) }
    var itemEmEdicao by remember { mutableStateOf<ItemAcervo?>(null) }
    var showBottomSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var itemParaExcluir by remember { mutableStateOf<ItemAcervo?>(null) }
    var mostrarConfirmacaoExclusao by remember { mutableStateOf(false) }

    // 1. O LAUNCHER DA GALERIA (O que já tínhamos)
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri -> if (uri != null) imagemSelecionadaUri = uri }
    )

    // 2. NOVO: O LAUNCHER DA CÂMERA!
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview(),
        onResult = { bitmap ->
            if (bitmap != null) {
                // Chama a nossa função ninja que salva a foto no cache
                imagemSelecionadaUri = salvarBitmapNoCache(context, bitmap)
            }
        }
    )

    val listaFiltrada = listaCompleta.filter { item ->
        val atendePesquisa = item.titulo.contains(pesquisaQuery, ignoreCase = true) || item.responsavel.contains(pesquisaQuery, ignoreCase = true)
        val atendeFiltro = filtroAtual == "Tudo" || item.tipo == filtroAtual
        atendePesquisa && atendeFiltro
    }

    if (mostrarConfirmacaoExclusao) {
        AlertDialog(
            onDismissRequest = { mostrarConfirmacaoExclusao = false },
            title = { Text("Excluir Item?") },
            text = { Text("Deseja realmente apagar '${itemParaExcluir?.titulo}'?") },
            confirmButton = {
                TextButton(onClick = {
                    itemParaExcluir?.let { coroutineScope.launch { dao.deletar(it) }; if (itemParaDetalhes == it) itemParaDetalhes = null }
                    mostrarConfirmacaoExclusao = false; itemParaExcluir = null
                }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Excluir") }
            }, dismissButton = { TextButton(onClick = { mostrarConfirmacaoExclusao = false }) { Text("Cancelar") } }
        )
    }

    Scaffold(
        containerColor = Color.Transparent,
        floatingActionButton = {
            if (itemParaDetalhes == null) {
                FloatingActionButton(onClick = {
                    itemEmEdicao = null; tituloInput = ""; responsavelInput = ""; tipoSelecionado = "Livro"; imagemSelecionadaUri = null; showBottomSheet = true
                }, containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.surface) { Icon(Icons.Default.Add, contentDescription = "Adicionar") }
            }
        }
    ) { paddingValues ->
        Crossfade(targetState = itemParaDetalhes, label = "Navegacao", modifier = Modifier.padding(paddingValues)) { telaDetalhe ->
            if (telaDetalhe == null) {
                Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(text = "Minha Estante", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary, fontFamily = FontFamily.Serif)
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(value = pesquisaQuery, onValueChange = { pesquisaQuery = it }, placeholder = { Text("Procurar nos ${listaCompleta.size} itens...") }, modifier = Modifier.fillMaxWidth(), leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) }, shape = RoundedCornerShape(28.dp))
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Tudo", "Livro", "Vinil", "CD").forEach { categoria ->
                            FilterChip(selected = filtroAtual == categoria, onClick = { filtroAtual = categoria }, label = { Text(categoria) })
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    LazyVerticalGrid(columns = GridCells.Adaptive(minSize = 140.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(bottom = 80.dp), modifier = Modifier.fillMaxSize()) {
                        items(listaFiltrada) { item ->
                            Card(onClick = { itemParaDetalhes = item }, modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f).background(MaterialTheme.colorScheme.secondaryContainer)) {
                                        if (item.imagemUri != null) AsyncImage(model = item.imagemUri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                        else Text(text = "Sem Capa", modifier = Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
                                    }
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text(text = item.titulo, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
                                        Text(text = "${item.tipo} • ${item.responsavel}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                                        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                                            IconButton(onClick = { itemEmEdicao = item; tituloInput = item.titulo; responsavelInput = item.responsavel; tipoSelecionado = item.tipo; imagemSelecionadaUri = item.imagemUri?.let { Uri.parse(it) } ; showBottomSheet = true }, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Edit, contentDescription = "Editar", tint = MaterialTheme.colorScheme.primary) }
                                            Spacer(modifier = Modifier.width(8.dp))
                                            IconButton(onClick = { itemParaExcluir = item; mostrarConfirmacaoExclusao = true }, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Delete, contentDescription = "Excluir", tint = MaterialTheme.colorScheme.error) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            else {
                BackHandler { itemParaDetalhes = null }
                Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { itemParaDetalhes = null }) { Icon(Icons.Default.ArrowBack, contentDescription = "Voltar", tint = MaterialTheme.colorScheme.primary) }
                        Text("Detalhes", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary, fontFamily = FontFamily.Serif)
                    }
                    Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f).background(MaterialTheme.colorScheme.secondaryContainer)) {
                        if (telaDetalhe.imagemUri != null) AsyncImage(model = telaDetalhe.imagemUri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(text = telaDetalhe.titulo, style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Serif, color = MaterialTheme.colorScheme.onSurface)
                        Text(text = "${telaDetalhe.tipo} • ${telaDetalhe.responsavel}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(24.dp))
                        Divider(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                        Spacer(modifier = Modifier.height(24.dp))

                        var anotacoesTexto by remember(telaDetalhe) { mutableStateOf(telaDetalhe.anotacoes ?: "") }

                        // O Auto-Save consertado!
                        OutlinedTextField(
                            value = anotacoesTexto,
                            onValueChange = { novoTexto ->
                                anotacoesTexto = novoTexto
                                val itemAtualizado = telaDetalhe.copy(anotacoes = novoTexto)
                                coroutineScope.launch { dao.salvar(itemAtualizado) }
                            },
                            label = { Text("Minhas Anotações / Review") }, modifier = Modifier.fillMaxWidth().height(150.dp), maxLines = 5
                        )
                        Spacer(modifier = Modifier.height(32.dp))
                    }
                }
            }
        }

        if (showBottomSheet) {
            ModalBottomSheet(onDismissRequest = { showBottomSheet = false }, sheetState = sheetState) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = if (itemEmEdicao == null) "Novo Cadastro" else "Editar Item", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(16.dp))

                    // --- A MÁGICA VISUAL AQUI: Dois botões lado a lado! ---
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { cameraLauncher.launch(null) },
                            modifier = Modifier.weight(1f)
                        ) { Text("📸 Câmera") }

                        Button(
                            onClick = { photoPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            modifier = Modifier.weight(1f)
                        ) { Text("🖼️ Galeria") }
                    }

                    // Aviso verde se a foto foi tirada ou escolhida com sucesso
                    if (imagemSelecionadaUri != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("✅ Imagem anexada com sucesso!", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Livro", "Vinil", "CD").forEach { categoria ->
                            FilterChip(selected = tipoSelecionado == categoria, onClick = { tipoSelecionado = categoria }, label = { Text(categoria) })
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(value = tituloInput, onValueChange = { tituloInput = it }, label = { Text("Título") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = responsavelInput, onValueChange = { responsavelInput = it }, label = { Text(if (tipoSelecionado == "Livro") "Autor" else "Artista") }, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = {
                            if (tituloInput.isNotBlank() && responsavelInput.isNotBlank()) {
                                val idParaSalvar = itemEmEdicao?.id ?: 0
                                val notasAntigas = itemEmEdicao?.anotacoes
                                val novoItem = ItemAcervo(id = idParaSalvar, titulo = tituloInput, responsavel = responsavelInput, tipo = tipoSelecionado, imagemUri = imagemSelecionadaUri?.toString(), anotacoes = notasAntigas)
                                coroutineScope.launch { dao.salvar(novoItem) }
                                showBottomSheet = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (itemEmEdicao == null) "Salvar no Acervo" else "Atualizar Item") }
                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
        }
    }
}