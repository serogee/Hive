# Hive

Hive is an offline-first Android app for creating question stacks, reviewing them alone, and hosting multiplayer quizzes over a local network or an online server.

## Repository layout

| Directory      | Contents                                                                              |
| -------------- | ------------------------------------------------------------------------------------- |
| `client/`      | Android app: Compose screens, ViewModels, local storage, networking, embedded host    |
| `server/`      | Standalone Ktor server. Only the demo configuration runs so far.                      |
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

Replace the demo data with fresh seed data (stop the server first):

```powershell
.\gradlew.bat :server:resetDemo
```

Build and install the debug app on a connected phone:

```powershell
.\gradlew.bat :client:installDebug
```

## Two-phone demo

The demo server stores its data in `server/data/hive-demo.db`. It has two fictional accounts, `alex` and `sam`, both with the password `hive-demo`. The seeded rooms use the codes `NETDSC` (Discussion lobby hosted by Alex), `NETASY` (Asynchronous room hosted by Sam), and `NETEND` (ended room whose results include a tie). When a server update changes the database layout, the next start replaces the demo data.

1. Connect the laptop and both phones to the same Wi-Fi network.
2. Start the demo server. It prints the addresses that phones can use, such as `http://192.168.1.20:8080`. Allow Java through Windows Firewall when asked.
3. On each phone, open Accounts, enter that address, and tap Test and save.
4. On the Host phone, sign in as `alex`, open Quiz Room, and tap the Discussion room under Rooms you host.
5. On the Player phone, stay signed out, enter `NETDSC` under Join a quiz, and join as a guest.
6. Both lobbies now list Alex Rivera as Host and the guest's name below.
7. On the Host phone, tap Start quiz. The Player phone shows the first question.
8. On the Player phone, choose an answer and tap Submit answer. It shows Answer received after the server saves it, and the Host phone counts the answer.
9. On the Host phone, close the question, then tap Next question or end the quiz. After the quiz ends, both phones show the results.
