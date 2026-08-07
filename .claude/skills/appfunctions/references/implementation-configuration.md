Specialized instructions for generating Kotlin implementations of AppFunctions, handling system-wide configuration, and managing build dependencies.

## Instructions

### Step 1: Configure Gradle dependencies and KSP

Add the following to `build.gradle.kts`. App Functions requires the KSP (Kotlin Symbol Processing) plugin.

1. **Version check** : Use library version `1.0.0-alpha10` or later from maven.google.com.

<br />

```kotlin
implementation(libs.androidx.appfunctions)
ksp(libs.androidx.appfunctions.compiler)
   
```

<br />

<br />

<br />

### Step 2: Set up app metadata XML

Describe the app's capabilities to the LLM by defining `res/xml/app_metadata.xml`.

<br />

```xml
<AppFunctionAppMetadata xmlns:appfn="http://schemas.android.com/apk/androidx.appfunctions"
    appfn:description="This app manages user tasks and reminders.
    Operational Patterns:
    - Use 'createTask' to add new tasks or reminders with titles and content.
    Constraints:
    - Title or content must be non-null when creating a task."
    appfn:displayDescription="@string/user_visible_description" />
   
```

<br />

Register the service and reference the app metadata in `AndroidManifest.xml` within the `<application>` tag:

<br />

```xml
<service
    android:name="com.example.snippets.ai.TaskAppFunctionService"
    android:permission="android.permission.BIND_APP_FUNCTION_SERVICE"
    android:exported="true"
    tools:targetApi="36">
    <property
        android:name="android.app.appfunctions.schema"
        android:value="app_functions_schema.xsd" />
    <property
        android:name="android.app.appfunctions.v2"
        android:value="task_app_function_service.xml" />
    <intent-filter>
        <action android:name="android.app.appfunctions.AppFunctionService" />
    </intent-filter>
</service>
<property
    android:name="android.app.appfunctions.app_metadata"
    android:resource="@xml/app_metadata" />
   
```

<br />

### Step 3: Implement functions

When generating Kotlin code for AppFunctions, you MUST adhere to these rules:

1. **Annotations** :
   - Annotate the function with `@AppFunction(isDescribedByKDoc = true)`.
   - Annotate associated data classes with `@AppFunctionSerializable(isDescribedByKDoc = true)`.
2. **Parameter strategy** :
   - **Specificity**: Keep parameters specific. State objects must be unambiguous.
   - **Optionality**: If a parameter isn't essential, make it optional with a default value.
3. **Execution and threading** :
   - Use `suspend` functions.
   - To avoid blocking the Android UI thread, always run AppFunction implementations on a background dispatcher, such as `withContext(Dispatchers.IO)`.
4. **Supported types** :
   - **Primitives** : `Int`, `Long`, `Float`, `Double`, `Boolean`
   - **Arrays** : `IntArray`, `LongArray`, `FloatArray`, `DoubleArray`, `BooleanArray`
   - **Native types** : `String`, `PendingIntent`, `Uri`, `LocalTime`, `LocalDate`, `LocalDateTime`, `Instant`. Prefer using `LocalDateTime` or `Instant` for date and time fields.
   - **Custom objects** : Classes annotated with `@AppFunctionSerializable`.
   - **Collections** : `List` of any supported non-primitive type
5. **Default values** :
   - Use defaults that align with the type's empty state, such as `0` for `Int`, `null` for nullable objects, and `emptyList()` for `List`.
6. **Error handling** :
   - Throw subclasses of `androidx.appfunctions.AppFunctionException` to report errors to callers.
7. **Security** :
   - Don't expose highly sensitive user data, such as passwords or financial details.
   - Don't expose irreversible destructive actions without confirmation steps.

### Step 4: Set up dependency injection and service entry points

In version 1.0.0-alpha10 and later, App Functions use the compile-time `@AppFunctionServiceEntryPoint` architecture. Create an abstract class extending `AppFunctionService` annotated with `@AppFunctionServiceEntryPoint`. KSP generates the concrete service class and XML schema.

#### Recommended approach with Hilt

Annotate your service with `@AndroidEntryPoint` and inject your data repositories or use cases using standard `@Inject internal lateinit var`:

<br />

```kotlin
@RequiresApi(36)
@AndroidEntryPoint
@AppFunctionServiceEntryPoint(
    serviceName = "ConfigAppFunctionServiceHeader",
    appFunctionXmlFileName = "config_app_function_service_header",
)
abstract class BaseAppFunctionServiceHeader : AppFunctionService() {
    @Inject internal lateinit var messageRepository: MessageRepository

    @AppFunction(isDescribedByKDoc = true)
    internal suspend fun send(
        name: String,
        endpointValue: String,
        messageBody: String,
    ): MessageResult {
        return messageRepository.send(name, endpointValue, messageBody)
    }
}
   
```

<br />

#### Framework-agnostic approach with alternative dependency injection or service locators

While Hilt is recommended, many Android applications implement AppFunctions with alternative dependency injection frameworks (like Koin, Anvil, or manual Service Locators). Because `AppFunctionService` inherits from Android `android.app.Service` (and therefore `Context`), you are able access your application's DI container directly through `applicationContext` in property getters or during service lifecycle execution:

<br />

```kotlin
@RequiresApi(36)
@AppFunctionServiceEntryPoint(
    serviceName = "ServiceLocatorConfigAppFunctionService",
    appFunctionXmlFileName = "service_locator_config_app_function_service",
)
abstract class BaseAppFunctionServiceLocator : AppFunctionService() {
    // Example using a manual Service Locator / Application container
    private val messageRepository by lazy {
        (applicationContext as AppFunctionApplication).appContainer.messageRepository
    }
    // Or with Koin / alternative DI locators:
    // private val messageRepository: MessageRepository by inject()

    @AppFunction(isDescribedByKDoc = true)
    internal suspend fun send(
        name: String,
        endpointValue: String,
        messageBody: String,
    ): MessageResult {
        return messageRepository.send(name, endpointValue, messageBody)
    }
}
   
```

<br />

### Step 5: Architectural cleanliness

Don't attempt to make an `AppFunction` class or method OS-agnostic---App Functions are inherently part of the Android platform integration in `androidx.appfunctions`. For architectural cleanliness, use existing application functionality (such as existing repositories, use cases, or domain orchestrators) to execute the behavior within your `@AppFunction` methods rather than creating redundant abstraction layers around the OS service.

<br />

## Critical constraints

### KSP compliance for serializables

**Critical constraints** : For `@AppFunctionSerializable` data classes, KSP only extracts documentation if it's written as inline KDoc directly for each property definition. Don't use class-level `@param` or `@property` tags.

### Package integrity

Configuration APIs and the `@AppFunction` annotation are located in `androidx.appfunctions`.

## Examples

### Example: Serializable with inline KDoc

<br />

```kotlin
/** The parameter to create the task. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class CreateTaskParams(
    /** The title of the task. */
    val title: String?,
    /** The content of the task. */
    val content: String?,
)

/** The user-created task. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class Task(
    /** The ID of the task. */
    val id: String,
    /** The title of the task. */
    val title: String,
    /** The content of the task. */
    val content: String,
)
   
```

<br />

<br />

### Example: Implementation detail

<br />

```kotlin
@RequiresApi(36)
@AndroidEntryPoint
@AppFunctionServiceEntryPoint(
    serviceName = "TaskAppFunctionService",
    appFunctionXmlFileName = "task_app_function_service",
)
abstract class BaseTaskAppFunctionService : AppFunctionService() {
    @Inject internal lateinit var taskRepository: TaskRepository

    /**
     * Creates a task based on [createTaskParams].
     *
     * @param createTaskParams The parameter to describe how to create the task.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun createTask(
        createTaskParams: CreateTaskParams,
    ): Task = withContext(Dispatchers.IO) {
        // Developers can use predefined exceptions to let the agent know
        // why it failed.
        if (createTaskParams.title == null && createTaskParams.content == null) {
            throw AppFunctionInvalidArgumentException("Title or content should be non-null")
        }

        val id = taskRepository.createTask(
            createTaskParams.title,
            createTaskParams.content
        )

        return@withContext taskRepository
            .getTask(id)
            ?.toTask()
            ?: throw AppFunctionElementNotFoundException("Task not found for ID = $id")
    }

    // Maps internal TaskEntity
    private fun TaskEntity.toTask() = Task(id = id, title = title, content = description)
}
   
```

<br />

<br />

## Troubleshooting

### Error: "AppFunction unavailable" or "Metadata missing"

**Cause**: The AppSearch indexing failed to extract the schema correctly.

**Solution**:

1. Verify `@AppFunctionSerializable` classes use inline KDoc comments, not class-level `@param` tags.
2. Check that the `assets/<appFunctionXmlFileName>.xml` file exists in the APK.
3. Confirm the `ksp("androidx.appfunctions:appfunctions-compiler")` dependency is correctly applied.
4. Ensure the `ksp` argument `appfunctions:aggregateAppFunctions` is set to `"true"`.