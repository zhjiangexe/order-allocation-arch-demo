# AGENTS.md instructions

## Language

- Unless the user explicitly requests another language, respond in Traditional Chinese (zh-TW).
- Keep code, command names, file paths, API names, and error messages in their original language.

## Java formatting

- Java formatting is defined by Palantir Java Format with a 120-column width.
- After modifying Java files, run `cd backend && ./gradlew spotlessApply`.
- Before committing Java changes, run `cd backend && ./gradlew spotlessCheck`.
