Optimizes AppFunction KDoc for AI agents and Model Context Protocol.

## Instructions

### Workflow: Agent-centric documentation

1. **Identify the core outcome** : Start the description with a strong imperative verb, for example, "Search", "Create", or "Update". Focus on the *user benefit*, not the code implementation.
2. **Workflow dependencies** : Explicitly state if another function must be called first using the standard phrase: **Required workflow: Call "Function A" first to "Objective"**.
3. **Parameter documentation** :
   - For **functions** : Use specific `@param` tags. Isolate validation rules and default values here.
   - For **serializables** : Use inline KDoc directly for each property declaration. KSP **won't** extract documentation from class-level tags.
4. **Error surface mapping** : Rewrite `@throws` descriptions to provide useful recovery steps for the AI agent, for example "If "Error", suggest the user check their internet connection."

### Workflow: Global app description for server instructions

When writing the `appfn:description` for `app_metadata.xml`, follow these instructions:

1. **Capture cross-function relationships**: Explain dependencies or sequences between tools, for example, "Always call 'authenticate' before fetching data.".
2. **Document operational patterns**: Guide the LLM on token-conserving usage, for example, "Use 'batch_update' over multiple 'update' calls."
3. **Specify constraints**: Define clear boundaries, for example, "File operations limited to workspace" or "Rate limit: 10 requests per minute."
4. **Anti-patterns** :
   - Don't repeat individual function descriptions.
   - Don't include marketing claims or subjective praise.
   - Don't attempt to prompt model personality or conversation style.

## Critical constraints

### Descriptive, not imperative

Describe what the function *does* , not what the LLM *must* do. Avoid phrases like "You must call this..." in favor of "This function provides...".

### No "fluff"

Remove conversational padding like "This method is used to..." or "Helpful for...". Be concise and technical.

### Inline KDoc for serializables

**Mandatory** : For `@AppFunctionSerializable` classes, documentation must be inline for each property. KSP ignores class-level `@param` or `@property` tags for these classes.

## Examples

### Example: MCP refactoring

**Original**:

`/** This function helps you find people. */`

**Refined**:

     /**
       * Search for message recipients by name or email.
       * Required workflow: Call this before "sendMessage" to obtain valid recipient IDs.
       * @param query Search string for name/email. If null, returns 3 most recent contacts.
       * @return List of "Recipient" objects matching the query.
       */

### Example: Global app description

**Refined**:

    This app provides functions for task management and team collaboration.

    Operational Patterns:
    - Always use 'searchUsers' to resolve user handles to internal IDs before calling 'assignTask'.
    - Prefer 'batchUpdateStatus' when modifying more than three tasks simultaneously to reduce latency.

    Constraints:
    - Task titles are limited to 100 characters.
    - Attachment uploads are limited to 5 MB.