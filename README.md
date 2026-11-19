# Hive

Hive is an offline-first Android app for creating question stacks, reviewing them alone, and hosting multiplayer quizzes over a local network or an online server.

## Repository layout

| Directory      | Contents                                                                              |
| -------------- | ------------------------------------------------------------------------------------- |
| `client/`      | Android app: Compose screens, ViewModels, local storage, networking, embedded host    |
| `server/`      | Standalone Ktor server with demo and production configurations                        |
| `shared/core/` | Plain Kotlin models, API contracts, and quiz rules with no Android or Ktor dependency |
| `shared/host/` | Reusable room service and Ktor room handlers for the server and the embedded host     |

## Requirements

- JDK 17 or later
- Android SDK with platform 37 (set `sdk.dir` in `local.properties`)

## Common commands

Run all JVM tests:

```powershell
.\gradlew.bat test
```

Start the demo server on port 8080:

```powershell
.\gradlew.bat :server:run
```

Build and install the debug app on a connected phone:

```powershell
.\gradlew.bat :client:installDebug
```
