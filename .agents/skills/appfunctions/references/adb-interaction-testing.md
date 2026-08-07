Provides commands to interact with AppFunctions on a connected device or emulator using ADB for AppFunction testing and debugging.

## Instructions

### Scenario 1: List app functions

Use this scenario when you want to see which app functions are registered on the device.

1. **List all functions** : To view all registered app functions in JSON format, run `adb shell cmd app_function list-app-functions`.
2. **Filter by package** : To view functions for a specific package, pipe the output to `grep` or a JSON tool: `adb shell cmd app_function list-app-functions | grep <package_name>`.

### Scenario 2: Invoke app functions

If you want to test the execution of an app function, use this scenario.

1. **Analyze description** : Before invoking, you must read the `description` field for the function in the `list-app-functions` output. This often contains critical usage constraints, required workflows, or disambiguation rules.
2. **Follow constraints**: Follow all instructions in the description, such as asking the user to disambiguate or calling another tool first.
3. **Format parameters** : Format the `--parameters` argument as a valid JSON string that represents the function's input arguments.
4. **Execute function** : Use `adb shell cmd app_function execute-app-function --package <PACKAGE_NAME> --function <SERVICE_CLASS_NAME#FUNCTION_NAME> --parameters '<PARAMETERS_JSON>'`.
5. **Handle response** : The command returns the result as a JSON string. To get brief YAML output, use `--brief-yaml`.

### Scenario 3: Manage function state

If you need to enable or disable an app function for testing, use this scenario.

1. **Set enabled state** : Use `adb shell cmd app_function set-enabled --package <PACKAGE_NAME> --function <SERVICE_CLASS_NAME#FUNCTION_NAME> --state <enable|disable|default>`.

## Critical constraints

### Follow metadata descriptions

**Mandatory** : The `description` field in the app function metadata is a set of instructions for the LLM. If a description says to "disambiguate with the user" or "call another function first," you must perform those steps before execution.

### JSON escaping

**Critical** : When passing JSON using `adb shell`, always wrap the JSON string in single quotes to prevent the shell from interpreting special characters or spaces. Example: `--parameters '{"key": "value"}'`.

### Device availability

The `app_function` service must be available on the device. If `cmd: Can't find service: app_function` is returned, the device doesn't support this feature.

## Examples

### Example 1: Verify app function service availability on connected device

    adb shell cmd app_function help

If executing the preceding command returns a help page, use the commands and parameters provided to guide the ADB interaction testing tool interactions.

### Example 2: List all registered app functions

     adb shell cmd app_function list-app-functions

### Example 3: Execute a "send message" function

     adb shell cmd app_function execute-app-function \
     --package com.example.messaging \
     --function
    'com.example.messaging.appfunctions.MessagingAppFunctionService#sendMessage' \
     --parameters '{"recipient": "Alice", "message": "Hello!"}'

### Example 4: Disable a specific function

     adb shell cmd app_function set-enabled --package com.example.app \
     --function 'com.example.app.appfunctions.SomeAppFunctionService#someFunction'
    --state disable

## Troubleshooting

### Error: "Unknown command"

**Cause**: You are likely using an older version of the instructions.

**Solution**: Look up supported commands using "Example 1".

### Error: "Function not found"

**Cause**: The function ID or package name is incorrect.

**Solution** : Run `list-app-functions` and search for the relevant identifiers in the JSON output.