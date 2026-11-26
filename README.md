# Hive

Hive is an offline-first Android app for creating question stacks, reviewing them alone, and hosting multiplayer quizzes over a local network or an online server.

## Repository layout

| Directory      | Contents                                                                                  |
| -------------- | ----------------------------------------------------------------------------------------- |
| `client/`      | Android app: Compose screens, ViewModels, local storage, networking, embedded host        |
| `server/`      | Standalone Ktor server, for demos with local data or for production with Supabase sign-in |
| `shared/core/` | Plain Kotlin models, API contracts, and quiz rules with no Android or Ktor dependency     |
| `shared/host/` | Reusable room service and Ktor room handlers for the server and the embedded host         |

## Requirements

- JDK 17 or later
- Android SDK with platform 37 (set `sdk.dir` in `local.properties`)

## Scripts

Run `.\run` in the repository root on Windows to list the commands, or double-click `run.cmd` for a menu. Each command runs a script in `scripts`, and every option after the command goes to that script unchanged.

- `.\run app build` runs the JVM tests, builds the debug app, and prints where it is.
- `.\run app dev [server URL]` installs the debug app on the connected phone or emulator and opens it.
- `.\run server dev [--reset]` starts the demo server on port 8080, with fresh demo data after `--reset`.
- `.\run server docker [--down | --reset]` builds the server image and runs it with Postgres in Docker, as it runs when it is hosted. `--down` stops both, and `--reset` also deletes the local database. Copy `.env.example` to `.env` to change the port or the local password.
- `.\run server run` builds the server and runs it with the settings in `server\.env`. Copy `server\.env.example` to make that file.
- `.\run app release --setup-key` makes the signing key for releases, one time. Back up the key and the settings file that it prints.
- `.\run app release 0.1.0` tests and builds a signed app from `main`, asks, then publishes the tag and the app as a GitHub release. The notes come from `releases\0.1.0.md`, or from the merges on `main` when there is no such file.

To add a command, add one line to the table at the top of `scripts\run.ps1`, and put its script in `scripts`. In VS Code, the same commands are in Terminal > Run Task.

Installed apps find new releases with Check for updates in Account > Settings. It reads the releases of `serogee/Hive` on GitHub, or of the `owner/name` in `hive.updatesRepo` in `gradle.properties`. It downloads only the `hive-<version>.apk` that `.\run app release` attaches, and it installs the file only when the package, the version, and the signing key match the installed app. Phones read GitHub without an account, so the repository must be public. A debug app can check, but it cannot install a release.

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

## Sign-in and the online server

Stacks, reviews, hosting on a phone, and joining nearby phones work without any of this. Google sign-in turns itself on when the app is built with three values. Put them in `%USERPROFILE%\.gradle\gradle.properties`, outside the repository:

```properties
hive.supabaseUrl=https://your-project.supabase.co
hive.supabaseKey=your-publishable-key
hive.googleWebClientId=your-web-client-id.apps.googleusercontent.com
hive.serverUrl=https://your-hive-server.example
```

`hive.serverUrl` is the server that the app uses at first. Without it, a release app has no server, and a debug app looks for a demo server on the computer of the emulator. Never put the Google client secret or a Supabase secret key in the app.

On the server, set `HIVE_MODE=production` and `SUPABASE_URL` in `server\.env`. Without `SUPABASE_URL`, the server starts and guests can join rooms, but nobody can sign in.

Set `DATABASE_URL` to the session pooler address of the Supabase project to keep rooms in Postgres. The server makes and updates its tables when it starts, in a schema that the publishable key cannot read. Without `DATABASE_URL`, rooms are kept in memory and end when the server stops.

Add `?sslmode=disable` only for a database on a private network, such as the one in `compose.yaml`. Every other address is encrypted.

`render.yaml` deploys the server to Render from `server/Dockerfile`. Create a blueprint from the repository in the Render dashboard, and enter `SUPABASE_URL` and `DATABASE_URL` there.

| Situation                                    | What users see                                                                                              |
| -------------------------------------------- | ----------------------------------------------------------------------------------------------------------- |
| A value is missing                           | Continue with Google is greyed out with a lock and "Sign-in is down right now"                              |
| Signed out, and the server cannot be reached | Online features are greyed out with a lock and "The server is down right now" or "The server is not set up" |
| Signed in, and the server cannot be reached  | Online features have a dashed outline and "Connection is currently unavailable". Users stay signed in.      |
| The server runs without `SUPABASE_URL`       | Features that need an account are greyed out with "Sign-in is down right now"                               |

Account shows the state of the server, with Retry when it is down. Debug apps also have the demo accounts for the demo server. Release apps do not.

## What syncs

Guests keep everything on their phone. A signed-in account syncs through a server that keeps accounts, which is the demo server or a production server with `DATABASE_URL`:

- Stacks, with every question. A deleted stack is deleted on the other phones too.
- Finished reviews, and the results of quizzes hosted on a phone or played on a nearby Host's phone.
- The account's online rooms, hosted and played, which come from the server itself.

The phone syncs after sign-in, when the app starts, a few seconds after a change, when the server can be reached again, and with Sync now in Account. Changes made offline wait on the phone. When two phones change the same stack, the account keeps the change that arrived first, and the other phone saves its own as a new stack, with "(from this phone)" after the title.

The first sign-in on a phone that already has stacks asks one time whether to upload them or keep them on that phone only. Log Out asks whether to keep the synced stacks on the phone. Reset Data asks whether to reset this phone only, or the account too. The account option deletes what the server keeps, after a second question.

## Two-phone demo with the demo server

The demo server stores its data in `server/data/hive-demo.db`. It has two fictional accounts, `alex` and `sam`, both with the password `hive-demo`. The seeded rooms use the codes `NETDSC` (Discussion lobby hosted by Alex), `NETASY` (Asynchronous room hosted by Sam), and `NETEND` (ended room whose results include a tie). When a server update changes the database layout, the next start replaces the demo data.

1. Connect the laptop and both phones to the same Wi-Fi network.
2. Start the demo server. It prints the addresses that phones can use, such as `http://192.168.1.20:8080`. Allow Java through Windows Firewall when asked.
3. On each phone, tap Use Hive without an account on the Welcome screen. Open Accounts, tap Settings, enter that address under Server Address, and tap Test and Save.
4. On the Host phone, sign in as `alex` under Account Sign In in the same Settings, open Quiz Room, and tap the Discussion room under Online Rooms.
5. On the Player phone, stay signed out, enter `NETDSC` as the room code on Home, tap Join, enter a name, and join as a guest.
6. Both lobbies now list Alex Rivera as Host and the guest's name below.
7. On the Host phone, tap Start quiz. The Player phone shows the first question.
8. On the Player phone, choose an answer and tap Confirm. It shows Answer received after the server saves it, and the Host phone counts the answer.
9. On the Host phone, close the question, then tap Next question or end the quiz. After the quiz ends, both phones show the results.

## Offline demo without a server

This checks that a fresh phone can create, review, and host a stack with no internet and no demo server.

1. Turn off mobile data on both phones. On the Host phone, turn on the hotspot. Connect the Player phone to that hotspot.
2. On both phones, tap Use Hive without an account on the Welcome screen. On the Host phone, tap Create Stack on Home and write a few questions. Tap Save.
3. On Home, tap Review, choose the stack, choose Quiz Review or Flashcard Review, and answer the questions.
4. On Home, tap Host Quiz and choose the stack. Keep On this phone selected, turn on Use my name or enter a name, and tap Create Room. The lobby shows the room code, and an address such as `192.168.43.1:8090`.
5. On the Player phone, enter the code on Home, tap Join, enter a name, and join as a guest. If the room is not found, enter the address from the lobby.
6. Run the quiz as in the demo above. After the quiz ends, the phone stops serving rooms by itself. To end a room early, open it in Quiz Room and tap End room for everyone.
