package com.example.motaalertas

import android.content.Context
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class Search(val name: String, val query: String, val maxPrice: Int?)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface { Screen() } } }
    }
}

private fun gh(method: String, repo: String, token: String, body: String? = null): Pair<Int, String> {
    val c = URL("https://api.github.com/repos/$repo/contents/filters.json").openConnection() as HttpURLConnection
    c.requestMethod = method
    c.setRequestProperty("Authorization", "Bearer $token")
    c.setRequestProperty("Accept", "application/vnd.github+json")
    if (body != null) {
        c.doOutput = true
        c.outputStream.use { it.write(body.toByteArray()) }
    }
    val code = c.responseCode
    val text = (if (code < 400) c.inputStream else c.errorStream).bufferedReader().readText()
    return code to text
}

private fun fetch(repo: String, token: String): Pair<List<Search>, String> {
    val (code, text) = gh("GET", repo, token)
    if (code != 200) error("GitHub $code")
    val o = JSONObject(text)
    val raw = String(Base64.decode(o.getString("content"), Base64.DEFAULT))
    val arr = JSONObject(raw).getJSONArray("searches")
    val list = (0 until arr.length()).map {
        val s = arr.getJSONObject(it)
        Search(s.getString("name"), s.getString("query"), if (s.has("max_price")) s.getInt("max_price") else null)
    }
    return list to o.getString("sha")
}

private fun save(repo: String, token: String, list: List<Search>) {
    val sha = fetch(repo, token).second
    val arr = JSONArray()
    list.forEach {
        arr.put(JSONObject().put("name", it.name).put("query", it.query).apply { it.maxPrice?.let { p -> put("max_price", p) } })
    }
    val content = Base64.encodeToString(JSONObject().put("searches", arr).toString(2).toByteArray(), Base64.NO_WRAP)
    val body = JSONObject().put("message", "filtros atualizados na app").put("content", content).put("sha", sha).toString()
    val (code, _) = gh("PUT", repo, token, body)
    if (code !in 200..201) error("GitHub $code")
}

@Composable
fun Screen() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    var repo by remember { mutableStateOf(prefs.getString("repo", "") ?: "") }
    var token by remember { mutableStateOf(prefs.getString("token", "") ?: "") }
    var searches by remember { mutableStateOf(listOf<Search>()) }
    var name by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }

    fun run(label: String, block: suspend () -> Unit) = scope.launch {
        status = "$label…"
        status = try {
            withContext(Dispatchers.IO) { block() }
            "$label: ok"
        } catch (e: Exception) {
            "Erro: ${e.message}"
        }
    }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Alertas de motas", style = MaterialTheme.typography.headlineSmall) }
        item { OutlinedTextField(repo, { repo = it }, label = { Text("Repositório (utilizador/repo)") }, modifier = Modifier.fillMaxWidth()) }
        item {
            OutlinedTextField(token, { token = it }, label = { Text("Token GitHub") },
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        }
        item {
            Button(onClick = {
                prefs.edit().putString("repo", repo).putString("token", token).apply()
                run("Carregar") { searches = fetch(repo, token).first }
            }) { Text("Ligar e carregar filtros") }
        }
        item { HorizontalDivider() }
        item { OutlinedTextField(name, { name = it }, label = { Text("Nome do alerta") }, modifier = Modifier.fillMaxWidth()) }
        item { OutlinedTextField(query, { query = it }, label = { Text("Pesquisa (ex: mota naked, scooter 125)") }, modifier = Modifier.fillMaxWidth()) }
        item { OutlinedTextField(price, { price = it.filter(Char::isDigit) }, label = { Text("Preço máximo (€)") }, modifier = Modifier.fillMaxWidth()) }
        item {
            Button(onClick = {
                if (query.isNotBlank()) {
                    searches = searches + Search(name.ifBlank { query }, query, price.toIntOrNull())
                    name = ""; query = ""; price = ""
                }
            }) { Text("Adicionar alerta") }
        }
        itemsIndexed(searches) { i, s ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${s.name} · ${s.query}" + (s.maxPrice?.let { " · até $it€" } ?: ""), Modifier.weight(1f))
                TextButton(onClick = { searches = searches.filterIndexed { j, _ -> j != i } }) { Text("Apagar") }
            }
        }
        item { Button(onClick = { run("Guardar") { save(repo, token, searches) } }) { Text("Guardar no GitHub") } }
        item { Text(status) }
    }
}
