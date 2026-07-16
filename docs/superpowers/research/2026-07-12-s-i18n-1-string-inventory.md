# S-i18n-1 product string inventory (Phase 1)
Generated: 2026-07-12T14:33Z

## Default catalog keys (app values/strings.xml)
about_title_about
about_title_feed_back
about_title_info
about_title_open_source
about_title_output
about_title_privacy_statement
about_title_privacy_statement_zh
about_title_rating
about_title_update_log
about_title_version
action_pick
action_save
action_settings
app_name
back
config_default_water_mark_text
copy
copy_failed
copy_success
crash_mail
dev_comment
dialog_button_add_template
dialog_cancel_exist_confirm
dialog_content_exist_confirm
dialog_export_to_gallery
dialog_open_in_gallery
dialog_save_config_format
dialog_save_config_quality
dialog_save_export_list_title
dialog_save_exporting
dialog_title_edit_watermark
dialog_title_exist_confirm
dialog_title_template_edit
dialog_title_template_title
email_subject
error_file_not_found
error_not_img
error_save_oom
msg_crash
open_source_desc_about_lib
open_source_desc_compressor
open_source_desc_material_components
pick_via_system
recovery_mode_closed
recovery_mode_tips
recovery_title
request_permission_failed
share
store_not_found
style_alpha
text_paint_fill
text_paint_stroke
text_typeface_bold
text_typeface_bold_italic
text_typeface_italic
text_typeface_normal
tile_mode_title_decal
tile_mode_title_repeat
tip_not_mail_found
tips_cancel_dialog
tips_cancel_output
tips_choose_color_dialog
tips_choose_other_file_type
tips_compress_create_uri_failed
tips_compress_images
tips_compress_ok
tips_compressing
tips_confirm_dialog
tips_database_init_error
tips_delete_template
tips_do_not_choose_image
tips_error
tips_input_text_can_not_be_empty
tips_list_empty
tips_need_compress_img
tips_not_app_can_open_images
tips_ok
tips_pick_image
tips_tip_title
tips_use_this_template
title_content
title_horizon_layout
title_layout
title_style
title_text_color
title_text_rotate
title_text_size
title_text_style
title_tile_mode
title_vertical_layout
turn_off_recovery_mode
water_mark_mode_image
water_mark_mode_text

## Locale files
- app/src/main/res/values-de-rDE/strings.xml (87 keys)
- app/src/main/res/values-es/strings.xml (87 keys)
- app/src/main/res/values-fr/strings.xml (87 keys)
- app/src/main/res/values-it/strings.xml (87 keys)
- app/src/main/res/values-ja/strings.xml (87 keys)
- app/src/main/res/values-nb-rNO/strings.xml (87 keys)
- app/src/main/res/values-nl/strings.xml (87 keys)
- app/src/main/res/values-nn/strings.xml (83 keys)
- app/src/main/res/values-pt-rBR/strings.xml (87 keys)
- app/src/main/res/values-pt/strings.xml (87 keys)
- app/src/main/res/values-ru/strings.xml (6 keys)
- app/src/main/res/values-ta/strings.xml (87 keys)
- app/src/main/res/values-uk/strings.xml (0 keys)
- app/src/main/res/values-zh-rCN/strings.xml (87 keys)
- app/src/main/res/values-zh-rTW/strings.xml (87 keys)
- app/src/main/res/values/strings.xml (93 keys)

## R.string usages (app)
  12 
   1 Use -h for short descriptions and --help for more details.
   1 USAGE:
   1 SEARCH OPTIONS:
   1 ripgrep 15.1.0
   1 ripgrep (rg) recursively searches the current directory for lines matching
   1 Project home page: https://github.com/BurntSushi/ripgrep
   1 POSITIONAL ARGUMENTS:
   1 OUTPUT OPTIONS:
   1 OUTPUT MODES:
   1 OTHER BEHAVIORS:
   1 LOGGING OPTIONS:
   1 INPUT OPTIONS:
   1 FILTER OPTIONS:
   1 automatically skip hidden files/directories and binary files.
   1 Andrew Gallant <jamslam@gmail.com>
   1 a regex pattern. By default, ripgrep will respect gitignore rules and
   1   rg [OPTIONS] PATTERN [PATH ...]
   1   <PATTERN>   A regular expression used for searching.
   1   <PATH>...   A file or directory to search.
   1   -z, --search-zip                Search in compressed files.
   1   -x, --line-regexp               Show matches surrounded by line boundaries.
   1   -w, --word-regexp               Show matches surrounded by word boundaries.
   1   -V, --version                   Print ripgrep's version.
   1   -v, --invert-match              Invert matching.
   1   -u, --unrestricted              Reduce the level of "smart" filtering.
   1   -U, --multiline                 Enable searching across multiple lines.
   1   -t, --type=TYPE                 Only search files matching TYPE.
   1   -T, --type-not=TYPE             Do not search files matching TYPE.
   1   -S, --smart-case                Smart case search.
   1   -s, --case-sensitive            Search case sensitively (default).
   1   -r, --replace=TEXT              Replace matches with the given text.
   1   -q, --quiet                     Do not print anything to stdout.
   1   -p, --pretty                    Alias for colors, headings and line numbers.
   1   -P, --pcre2                     Enable PCRE2 matching.
   1   -o, --only-matching             Print only matched parts of a line.
   1   -N, --no-line-number            Suppress line numbers.
   1   -n, --line-number               Show line numbers.
   1   -m, --max-count=NUM             Limit the number of matching lines.
   1   -M, --max-columns=NUM           Omit lines longer than this limit.
   1   -L, --follow                    Follow symbolic links.
   1   -l, --files-with-matches        Print the paths with at least one match.
   1   -j, --threads=NUM               Set the approximate number of threads to use.
   1   -I, --no-filename               Never print the path with each matching line.
   1   -i, --ignore-case               Case insensitive search.
   1   -H, --with-filename             Print the file path with each matching line.
   1   -h, --help                      Show help output.
   1   -g, --glob=GLOB                 Include or exclude file paths.
   1   -F, --fixed-strings             Treat all patterns as literals.
   1   -f, --file=PATTERNFILE          Search for patterns from the given file.
   1   -e, --regexp=PATTERN            A pattern to search for.
   1   -E, --encoding=ENCODING         Specify the text encoding of files to search.
   1   -d, --max-depth=NUM             Descend at most NUM directories.
   1   -c, --count                     Show count of matching lines for each file.
   1   -C, --context=NUM               Show NUM lines before and after each match.
   1   -b, --byte-offset               Print the byte offset for each matching line.
   1   -B, --before-context=NUM        Show NUM lines before each match.
   1   -a, --text                      Search binary files as if they were text.
   1   -A, --after-context=NUM         Show NUM lines after each match.
   1   -0, --null                      Print a NUL byte after file paths.
   1   -., --hidden                    Search hidden files and directories.
   1   --vimgrep                       Print results in a vim compatible format.
   1   --type-list                     Show all supported file types.
   1   --type-clear=TYPE               Clear globs for a file type.
   1   --type-add=TYPESPEC             Add a new glob for a file type.
   1   --trim                          Trim prefix whitespace from matches.
   1   --trace                         Show trace messages.
   1   --stop-on-nonmatch              Stop searching after a non-match.
   1   --stats                         Print statistics about the search.
   1   --sortr=SORTBY                  Sort results in descending order.
   1   --sort=SORTBY                   Sort results in ascending order.
   1   --sort-files                    (DEPRECATED) Sort results by file path.
   1   --regex-size-limit=NUM          The size limit of the compiled regex.
   1   --pre=COMMAND                   Search output of COMMAND for each PATH.
   1   --pre-glob=GLOB                 Include or exclude files from a preprocessor.
   1   --pcre2-version                 Print the version of PCRE2 that ripgrep uses.
   1   --path-separator=SEP            Set the path separator for printing paths.
   1   --passthru                      Print both matching and non-matching lines.
   1   --one-file-system               Skip directories on other file systems.
   1   --null-data                     Use NUL as a line terminator.
   1   --no-unicode                    Disable Unicode mode.
   1   --no-require-git                Use .gitignore outside of git repositories.
   1   --no-pcre2-unicode              (DEPRECATED) Disable Unicode mode for PCRE2.
   1   --no-messages                   Suppress some error messages.
   1   --no-ignore-vcs                 Don't use ignore files from source control.
   1   --no-ignore-parent              Don't use ignore files in parent directories.
   1   --no-ignore-messages            Suppress gitignore parse error messages.
   1   --no-ignore-global              Don't use global ignore files.
   1   --no-ignore-files               Don't use --ignore-file arguments.
   1   --no-ignore-exclude             Don't use local exclusion files.
   1   --no-ignore-dot                 Don't use .ignore or .rgignore files.
   1   --no-ignore                     Don't use ignore files.
   1   --no-config                     Never read configuration files.
   1   --multiline-dotall              Make '.' match line terminators.
   1   --mmap                          Search with memory maps when possible.
   1   --max-filesize=NUM              Ignore files larger than NUM in size.
   1   --max-columns-preview           Show preview for lines exceeding the limit.
   1   --line-buffered                 Force line buffering.
   1   --json                          Show search results in a JSON Lines format.
   1   --include-zero                  Include zero matches in summary output.
   1   --ignore-file=PATH              Specify additional ignore files.
   1   --ignore-file-case-insensitive  Process ignore files case insensitively.
   1   --iglob=GLOB                    Include/exclude paths case insensitively.
   1   --hyperlink-format=FORMAT       Set the format of hyperlinks.
   1   --hostname-bin=COMMAND          Run a program to get this system's hostname.
   1   --heading                       Print matches grouped by each file.
   1   --glob-case-insensitive         Process all glob patterns case insensitively.
   1   --generate=KIND                 Generate man pages and completion scripts.
   1   --files-without-match           Print the paths that contain zero matches.
   1   --files                         Print each file that would be searched.
   1   --field-match-separator=SEP     Set the field match separator.
   1   --field-context-separator=SEP   Set the field context separator.
   1   --engine=ENGINE                 Specify which regex engine to use.
   1   --dfa-size-limit=NUM            The upper size limit of the regex DFA.
   1   --debug                         Show debug messages.
   1   --crlf                          Use CRLF line terminators (nice for Windows).
   1   --count-matches                 Show count of every match for each file.
   1   --context-separator=SEP         Set the separator for contextual chunks.
   1   --column                        Show column numbers.
   1   --colors=COLOR_SPEC             Configure color settings and styles.
   1   --color=WHEN                    When to use color.
   1   --block-buffered                Force block buffering.
   1   --binary                        Search binary files.
   1   --auto-hybrid-regex             (DEPRECATED) Use PCRE2 if appropriate.

## String bag types (shared)
shared/src/commonMain/kotlin/me/rosuh/easywatermark/ui/EditorScreen.kt:29:data class EditorUiStrings(
shared/src/commonMain/kotlin/me/rosuh/easywatermark/ui/about/AboutScreenShell.kt:38:data class AboutScreenStrings(
shared/src/commonMain/kotlin/me/rosuh/easywatermark/ui/compose/TemplateListSheet.kt:250:data class TemplateListSheetStrings(
shared/src/commonMain/kotlin/me/rosuh/easywatermark/ui/RecoveryScreen.kt:107:data class RecoveryScreenStrings(
shared/src/commonMain/kotlin/me/rosuh/easywatermark/ui/about/OpenSourceScreen.kt:79:data class OpenSourceScreenStrings(
shared/src/commonMain/kotlin/me/rosuh/easywatermark/ui/save/SaveExportSheetShell.kt:21:data class SaveExportSheetStrings(
shared/src/commonMain/kotlin/me/rosuh/easywatermark/ui/compose/TextColorOption.kt:25:data class TextColorOptionStrings(
shared/src/commonMain/kotlin/me/rosuh/easywatermark/ui/compose/TextContentOption.kt:45:data class TextContentOptionStrings(

## Phase 1 migrate parity (composeResources vs default)
Default keys: 94 (incl. cmp_spike_hello)
Locales under shared/src/commonMain/composeResources:
- values: 94 keys
- values-de-rDE: 87 keys
- values-es: 87 keys
- values-fr: 87 keys
- values-it: 87 keys
- values-ja: 87 keys
- values-nb-rNO: 87 keys
- values-nl: 87 keys
- values-nn: 83 keys
- values-pt: 87 keys
- values-pt-rBR: 87 keys
- values-ru: 6 keys
- values-ta: 87 keys
- values-uk: 0 keys
- values-zh-rCN: 88 keys
- values-zh-rTW: 87 keys

## Tests
- CmpSpikeResourcesTest + ComposeResourcesCatalogTest (desktopTest) green
- :app:assembleDebug green
