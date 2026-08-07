When creating a Glimmer List component, refer to the following source code in `GlimmerLazyColumnSamples.kt`:

<br />

```kotlin
@Composable
fun GlimmerLazyColumnSample() {
    GlimmerLazyColumn {
        item { ListItem { Text("Header") } }
        items(count = 10) { index -> ListItem { Text("Item-$index") } }
        item { ListItem { Text("Footer") } }
    }
}
   
```

```kotlin
@Composable
fun GlimmerLazyColumnWithTitleChipSample() {
    val ingredientItems =
        listOf("Milk", "Flour", "Egg", "Salt", "Apples", "Butter", "Vanilla", "Sugar", "Cinnamon")
    GlimmerLazyColumn(title = { TitleChip { Text("Ingredients") } }) {
        items(ingredientItems) { text -> ListItem { Text(text) } }
    }
}
   
```

<br />