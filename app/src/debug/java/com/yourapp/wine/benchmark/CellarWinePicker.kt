package com.yourapp.wine.benchmark

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun CellarWinePicker(label:String,vm:OcrLabViewModel,onDismiss:()->Unit,onSelect:(CellarWineChoice)->Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val search by vm.cellarSearch.collectAsState()
    LaunchedEffect(query) { vm.searchCellar(query) }
    AlertDialog(onDismissRequest=onDismiss,title={Text("Choose wine from cellar")},text={
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("For $label. Selecting a wine replaces all six actual fields. Review the vintage and photo before running. Your cellar is not changed.")
            OutlinedTextField(query,{query=it},label={Text("Maker, wine, vintage or location")},singleLine=true,modifier=Modifier.fillMaxWidth())
            when {
                search.loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                search.error!=null -> { Text(search.error.orEmpty());TextButton(onClick={vm.searchCellar(query)}) { Text("Retry") } }
                search.wines.isEmpty() -> Text("No matching wines currently in your cellar. Try another search or enter details manually.")
                else -> {
                    if(search.wines.size==CellarWineSearch.LIMIT) Text("Showing the first ${CellarWineSearch.LIMIT} matches. Narrow your search to find more.")
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max=320.dp)) {
                        items(search.wines,key={it.bottleId}) { wine ->
                            TextButton(onClick={onSelect(wine)},modifier=Modifier.fillMaxWidth()) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(wine.title)
                                    Text(listOf(wine.country,wine.region,wine.subregion).filter(String::isNotBlank).joinToString(" · "),
                                        style=MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
            Text("Blank / NV vintages remain unscored. Saved details are editable and are never supplied to OCR.",style=MaterialTheme.typography.bodySmall)
        }
    },confirmButton={},dismissButton={TextButton(onClick=onDismiss) { Text("Cancel") }})
}
