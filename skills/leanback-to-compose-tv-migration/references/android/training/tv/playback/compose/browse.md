A media app that runs on a TV needs to allow users to browse its content offerings, make a selection, and start playing content. The content browsing experience for apps of this type should be simple, intuitive, and visually pleasing and engaging.

A media catalog browser tends to consist of several sections, and each section has a list of media content. Examples of sections in a media catalog include: playlists, featured content, recommended categories.
![](https://developer.android.com/static/training/tv/images/compose-catalogbrowser-example.png) **Figure 1.** Typical catalog screen. Users are able to browse video catalog data.

Use the functions provided by Compose for TV to implement a user interface for browsing music or videos from your app's media catalog.

## Create a composable function for catalog

Everything appearing on a display is implemented as a composable function in Compose for TV. Start by defining a composable function for the media catalog browser:

    @Composable
    fun CatalogBrowser(
       featuredContentList: List<Movie>,
       sectionList: List<Section>,
       modifier: Modifier = Modifier,
       onItemSelected: (Movie) -> Unit = {},
    ) {
    // ToDo: add implementation
    }

`CatalogBrowser` is the composable function implementing your media catalog browser. The function takes the following arguments:

- List of featured content.
- List of sections.
- A Modifier object.
- A callback function, which triggers a screen transition.

## Set UI elements

Compose for TV offers lazy lists, a component to display a large number of items (or a list of an unknown length). Call [`LazyColumn`](https://developer.android.com/reference/kotlin/androidx/compose/foundation/lazy/package-summary#LazyColumn(androidx.compose.ui.Modifier,androidx.compose.foundation.lazy.LazyListState,androidx.compose.foundation.layout.PaddingValues,kotlin.Boolean,androidx.compose.foundation.layout.Arrangement.Vertical,androidx.compose.ui.Alignment.Horizontal,androidx.compose.foundation.gestures.FlingBehavior,kotlin.Boolean,kotlin.Function1)) to place sections vertically. `LazyColumn` provides a [`LazyListScope.() -> Unit`](https://developer.android.com/reference/kotlin/androidx/compose/foundation/lazy/LazyListScope) block, which offers a DSL to define item contents. In the following example, each section is placed in a vertical list with a 16 dp gap between sections:

    @Composable
    fun CatalogBrowser(
       featuredContentList: List<Movie>,
       sectionList: List<Section>,
       modifier: Modifier = Modifier,
       onItemSelected: (Movie) -> Unit = {},
    ) {
      LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
      ) {
        items(sectionList) { section ->
          Section(section, onItemSelected = onItemSelected)
        }
      }
    }

In the example, `Section` composable function defines how to display sections. In the following function, [`LazyRow`](https://developer.android.com/reference/kotlin/androidx/compose/foundation/lazy/package-summary#LazyRow(androidx.compose.ui.Modifier,androidx.compose.foundation.lazy.LazyListState,androidx.compose.foundation.layout.PaddingValues,kotlin.Boolean,androidx.compose.foundation.layout.Arrangement.Horizontal,androidx.compose.ui.Alignment.Vertical,androidx.compose.foundation.gestures.FlingBehavior,kotlin.Boolean,kotlin.Function1)) demonstrates how this horizontal version of [`LazyColumn`](https://developer.android.com/reference/kotlin/androidx/compose/foundation/lazy/package-summary#LazyColumn(androidx.compose.ui.Modifier,androidx.compose.foundation.lazy.LazyListState,androidx.compose.foundation.layout.PaddingValues,kotlin.Boolean,androidx.compose.foundation.layout.Arrangement.Vertical,androidx.compose.ui.Alignment.Horizontal,androidx.compose.foundation.gestures.FlingBehavior,kotlin.Boolean,kotlin.Function1)) is similarly used to define a horizontal list with a `LazyListScope.() -> Unit` block by calling the provided DSL:

    @Composable
    fun Section(
      section: Section,
      modifier: Modifier = Modifier,
      onItemSelected: (Movie) -> Unit = {},
    ) {
      Text(
        text = section.title,
        style = MaterialTheme.typography.headlineSmall,
      )
      LazyRow(
         modifier = modifier,
         horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        items(section.movieList){ movie ->
        MovieCard(
             movie = movie,
             onClick = { onItemSelected(movie) }
           )
        }
      }
    }

In the `Section` composable, the [`Text`](https://developer.android.com/reference/kotlin/androidx/tv/material3/package-summary#Text(androidx.compose.ui.text.AnnotatedString,androidx.compose.ui.Modifier,androidx.compose.ui.graphics.Color,androidx.compose.ui.unit.TextUnit,androidx.compose.ui.text.font.FontStyle,androidx.compose.ui.text.font.FontWeight,androidx.compose.ui.text.font.FontFamily,androidx.compose.ui.unit.TextUnit,androidx.compose.ui.text.style.TextDecoration,androidx.compose.ui.text.style.TextAlign,androidx.compose.ui.unit.TextUnit,androidx.compose.ui.text.style.TextOverflow,kotlin.Boolean,kotlin.Int,kotlin.collections.Map,kotlin.Function1,androidx.compose.ui.text.TextStyle)) component is used. Text and other components defined in Material Design are offered in the tv-material library . You can change the texts' style as defined in Material Design by referring to the [`MaterialTheme`](https://developer.android.com/reference/kotlin/androidx/tv/material3/MaterialTheme) object. This object is also provided by the tv-material library. [`Card`](https://developer.android.com/reference/kotlin/androidx/tv/material3/package-summary#Card(kotlin.Function0,androidx.compose.ui.Modifier,androidx.tv.material3.CardShape,androidx.tv.material3.CardColors,androidx.tv.material3.CardScale,androidx.tv.material3.CardBorder,androidx.tv.material3.CardGlow,androidx.compose.foundation.interaction.MutableInteractionSource,kotlin.Function1)) is part of the tv-material library. `MovieCard` defines how each movie data is rendered in the catalog defined as the following snippet:

    @Composable
    fun MovieCard(
       movie: Movie,
       modifier: Modifier = Modifier,
       onClick: () -> Unit = {}
    ) {
       Card(modifier = modifier, onClick = onClick){
        AsyncImage(
           model = movie.thumbnailUrl,
           contentDescription = movie.title,
         )
       }
    }

> [!NOTE]
> **Note:** `AsyncImage` is a composable that loads an image from the internet. See [Loading Images](https://developer.android.com/develop/ui/compose/graphics/images/loading#internet-loading) for details.

## Highlight featured content

In the example described earlier, all movies are displayed equally. They have the same area, no visual difference between them. You can highlight some of them with [`Carousel`](https://developer.android.com/reference/kotlin/androidx/tv/material3/Carousel.composable#Carousel(kotlin.Int,androidx.compose.ui.Modifier,androidx.tv.material3.CarouselState,kotlin.Long,androidx.compose.animation.ContentTransform,androidx.compose.animation.ContentTransform,kotlin.Function1,kotlin.Function2)).

Carousel displays the information in a set of items that can slide, fade, or move into view. You use the component to highlight featured content, such as newly available movies or new episodes of TV programs.

[`Carousel`](https://developer.android.com/reference/kotlin/androidx/tv/material3/Carousel.composable#Carousel(kotlin.Int,androidx.compose.ui.Modifier,androidx.tv.material3.CarouselState,kotlin.Long,androidx.compose.animation.ContentTransform,androidx.compose.animation.ContentTransform,kotlin.Function1,kotlin.Function2)) expects you to at least specify the number of items that Carousel has and how to draw each item. The first one can be specified with `itemCount`. The second one can be passed as a lambda. The index number of the displayed item is given to the lambda. You can determine the displayed item with the given index value:

    @Composable
    function FeaturedCarousel(
      featuredContentList: List<Movie>,
      modifier: Modifier = Modifier,
    ) {
      Carousel(
        itemCount = featuredContentList.size,
        modifier = modifier,
      ) { index ->
        val content = featuredContentList[index]
        Box {
          AsyncImage(
            model = content.backgroundImageUrl,
            contentDescription = content.description,
            placeholder = painterResource(
              id = R.drawable.placeholder
            ),
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
          )
          Text(text = content.title)
        }
      }
    }

`Carousel` can be an item of a lazy list, such as `TvLazyColumn`. The following snippet shows `FeaturedCarousel` composable on top of the all `Section` composables:

    @Composable
    fun CatalogBrowser(
       featuredContentList: List<Movie>,
       sectionList: List<Section>,
       modifier: Modifier = Modifier,
       onItemSelected: (Movie) -> Unit = {},
    ) {
      TvLazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
      ) {

        item {
          FeaturedCarousel(featuredContentList)
        }

        items(sectionList) { section ->
          Section(section, onItemSelected = onItemSelected)
        }
      }
    }